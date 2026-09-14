package com.example.delivery_project.service

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanPriorityDriverRepository
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.spec.Location
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

/**
 * 배송 업무 수령의 계약을 고정한다.
 *
 * 실제 DB 경합은 [com.example.delivery_project.domain.repository.DeliveryPlanClaimConcurrencyTest] 가 검증하고,
 * 여기서는 분기 처리와 **호출 순서**(기사 락이 계획 조회보다 먼저)를 고정해 회귀를 막는다.
 */
class DeliveryPlanClaimServiceTest {
    private val deliveryPlanRepository = mock<DeliveryPlanRepository>()
    private val priorityDriverRepository = mock<DeliveryPlanPriorityDriverRepository>()
    private val userRepository = mock<UserRepository>()
    private val claimProperties = DeliveryClaimProperties()
    private lateinit var service: DeliveryPlanClaimService

    private lateinit var driver: User

    @BeforeEach
    fun setUp() {
        claimProperties.maxActivePlans = 3
        service = DeliveryPlanClaimService(
            deliveryPlanRepository,
            priorityDriverRepository,
            userRepository,
            claimProperties,
            SimpleMeterRegistry(),
        )
        driver = User.of(DRIVER_ID, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)
    }

    @Test
    fun `미배정 업무를 수령하면 조건부 UPDATE 를 수행하고 배정 결과를 돌려준다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID))
            .thenReturn(openPlan())
            .thenReturn(claimedPlan(driver))
        whenever(deliveryPlanRepository.countByDriverIdAndStatusIn(eq(DRIVER_ID), any())).thenReturn(1L)
        whenever(deliveryPlanRepository.claimIfOpen(eq(PLAN_ID), eq(DRIVER_ID), any())).thenReturn(1)

        val response = service.claim(PLAN_ID, DRIVER_ID)

        assertThat(response.alreadyOwned).isFalse()
        assertThat(response.driverId).isEqualTo(DRIVER_ID)
        assertThat(response.status).isEqualTo(DeliveryPlanStatus.READY)
        assertThat(response.activePlanCount).isEqualTo(2L)
    }

    /**
     * 이 순서가 뒤집히면 MySQL REPEATABLE READ 에서 보유 한도 검사가 낡은 스냅샷을 읽게 되어
     * 한도를 초과한 수령이 가능해진다. 격리 수준과 함께 동시성 보장의 전제이므로 순서를 고정한다.
     */
    @Test
    fun `기사 행 잠금이 계획 조회보다 먼저 수행된다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID))
            .thenReturn(openPlan())
            .thenReturn(claimedPlan(driver))
        whenever(deliveryPlanRepository.claimIfOpen(eq(PLAN_ID), eq(DRIVER_ID), any())).thenReturn(1)

        service.claim(PLAN_ID, DRIVER_ID)

        val ordered = inOrder(userRepository, deliveryPlanRepository)
        ordered.verify(userRepository).findUserByIdForUpdate(DRIVER_ID)
        ordered.verify(deliveryPlanRepository).findWithDriverById(PLAN_ID)
        ordered.verify(deliveryPlanRepository).countByDriverIdAndStatusIn(eq(DRIVER_ID), any())
        ordered.verify(deliveryPlanRepository).claimIfOpen(eq(PLAN_ID), eq(DRIVER_ID), any())
    }

    @Test
    fun `이미 본인이 수령한 업무를 다시 요청하면 멱등하게 200 을 돌려준다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID)).thenReturn(claimedPlan(driver))
        whenever(deliveryPlanRepository.countByDriverIdAndStatusIn(eq(DRIVER_ID), any())).thenReturn(1L)

        val response = service.claim(PLAN_ID, DRIVER_ID)

        assertThat(response.alreadyOwned).isTrue()
        assertThat(response.driverId).isEqualTo(DRIVER_ID)
        verify(deliveryPlanRepository, never()).claimIfOpen(any(), any(), any())
    }

    @Test
    fun `다른 기사가 이미 수령한 업무는 409 로 거절한다`() {
        val otherDriver = User.of(99L, "other", "password", "다른기사", Role.ROLE_DELIVERY_DRIVER)
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID)).thenReturn(claimedPlan(otherDriver))

        assertThat(assertThrows<BusinessException> { service.claim(PLAN_ID, DRIVER_ID) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_ALREADY_CLAIMED)
        verify(deliveryPlanRepository, never()).claimIfOpen(any(), any(), any())
    }

    @Test
    fun `동시 보유 한도에 도달하면 조건부 UPDATE 전에 거절한다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID)).thenReturn(openPlan())
        whenever(deliveryPlanRepository.countByDriverIdAndStatusIn(eq(DRIVER_ID), any())).thenReturn(3L)

        assertThat(assertThrows<BusinessException> { service.claim(PLAN_ID, DRIVER_ID) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED)
        verify(deliveryPlanRepository, never()).claimIfOpen(any(), any(), any())
    }

    @Test
    fun `조건부 UPDATE 가 밀렸어도 이미 본인 소유면 멱등 응답을 돌려준다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID))
            .thenReturn(openPlan())
            .thenReturn(claimedPlan(driver))
        whenever(deliveryPlanRepository.countByDriverIdAndStatusIn(eq(DRIVER_ID), any())).thenReturn(0L)
        whenever(deliveryPlanRepository.claimIfOpen(eq(PLAN_ID), eq(DRIVER_ID), any())).thenReturn(0)

        val response = service.claim(PLAN_ID, DRIVER_ID)

        assertThat(response.alreadyOwned).isTrue()
    }

    @Test
    fun `조건부 UPDATE 가 밀리고 다른 기사 소유면 409 로 거절한다`() {
        val otherDriver = User.of(99L, "other", "password", "다른기사", Role.ROLE_DELIVERY_DRIVER)
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID))
            .thenReturn(openPlan())
            .thenReturn(claimedPlan(otherDriver))
        whenever(deliveryPlanRepository.countByDriverIdAndStatusIn(eq(DRIVER_ID), any())).thenReturn(0L)
        whenever(deliveryPlanRepository.claimIfOpen(eq(PLAN_ID), eq(DRIVER_ID), any())).thenReturn(0)

        assertThat(assertThrows<BusinessException> { service.claim(PLAN_ID, DRIVER_ID) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_ALREADY_CLAIMED)
    }

    @Test
    fun `우선 수령 구간에는 우선권이 없는 기사를 조건부 UPDATE 전에 거절한다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID)).thenReturn(priorityWindowPlan())
        whenever(priorityDriverRepository.existsByDeliveryPlanIdAndDriverId(PLAN_ID, DRIVER_ID)).thenReturn(false)

        assertThat(assertThrows<BusinessException> { service.claim(PLAN_ID, DRIVER_ID) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE)
        verify(deliveryPlanRepository, never()).claimIfOpen(any(), any(), any())
    }

    @Test
    fun `우선 수령 구간이어도 우선권을 가진 기사는 수령한다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID))
            .thenReturn(priorityWindowPlan())
            .thenReturn(claimedPlan(driver))
        whenever(priorityDriverRepository.existsByDeliveryPlanIdAndDriverId(PLAN_ID, DRIVER_ID)).thenReturn(true)
        whenever(deliveryPlanRepository.claimIfOpen(eq(PLAN_ID), eq(DRIVER_ID), any())).thenReturn(1)

        assertThat(service.claim(PLAN_ID, DRIVER_ID).alreadyOwned).isFalse()
    }

    @Test
    fun `공개 시각이 지나면 우선권이 없어도 수령할 수 있다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID))
            .thenReturn(publishedPlan())
            .thenReturn(claimedPlan(driver))
        whenever(deliveryPlanRepository.claimIfOpen(eq(PLAN_ID), eq(DRIVER_ID), any())).thenReturn(1)

        assertThat(service.claim(PLAN_ID, DRIVER_ID).alreadyOwned).isFalse()
        // 공개된 업무에는 우선권 조회 자체가 필요 없다.
        verify(priorityDriverRepository, never()).existsByDeliveryPlanIdAndDriverId(any(), any())
    }

    /**
     * 사전 검사를 통과한 뒤 공개 시각이 지나기 전에 다른 우선권 기사가 가져간 경우와,
     * 우선권이 없어 DB 조건에서 걸러진 경우는 클라이언트 대응이 다르므로 사유를 구분해야 한다.
     */
    @Test
    fun `조건부 UPDATE 가 우선권 조건에서 걸리면 우선 수령 구간 사유로 응답한다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID))
            .thenReturn(openPlan())
            .thenReturn(priorityWindowPlan())
        whenever(priorityDriverRepository.existsByDeliveryPlanIdAndDriverId(PLAN_ID, DRIVER_ID)).thenReturn(false)
        whenever(deliveryPlanRepository.claimIfOpen(eq(PLAN_ID), eq(DRIVER_ID), any())).thenReturn(0)

        assertThat(assertThrows<BusinessException> { service.claim(PLAN_ID, DRIVER_ID) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE)
    }

    @Test
    fun `배송 기사가 아니면 수령할 수 없다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(null)

        assertThat(assertThrows<BusinessException> { service.claim(PLAN_ID, DRIVER_ID) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_DRIVER_NOT_FOUND)
        verify(deliveryPlanRepository, never()).findWithDriverById(any())
    }

    @Test
    fun `배송 시작 전에는 수령한 업무를 반납할 수 있다`() {
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID)).thenReturn(claimedPlan(driver))
        whenever(deliveryPlanRepository.releaseIfOwnedAndReady(PLAN_ID, DRIVER_ID)).thenReturn(1)

        service.release(PLAN_ID, DRIVER_ID)

        verify(deliveryPlanRepository).releaseIfOwnedAndReady(PLAN_ID, DRIVER_ID)
    }

    @Test
    fun `배송을 시작한 뒤에는 반납할 수 없다`() {
        val plan = claimedPlan(driver)
        plan.addStop("배송지", 37.51, 126.91, LocalDateTime.now())
        plan.start()
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID)).thenReturn(plan)

        assertThat(assertThrows<BusinessException> { service.release(PLAN_ID, DRIVER_ID) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_NOT_RELEASABLE)
        verify(deliveryPlanRepository, never()).releaseIfOwnedAndReady(any(), any())
    }

    @Test
    fun `본인이 수령하지 않은 업무는 반납할 수 없다`() {
        val otherDriver = User.of(99L, "other", "password", "다른기사", Role.ROLE_DELIVERY_DRIVER)
        whenever(userRepository.findUserByIdForUpdate(DRIVER_ID)).thenReturn(driver)
        whenever(deliveryPlanRepository.findWithDriverById(PLAN_ID)).thenReturn(claimedPlan(otherDriver))

        assertThat(assertThrows<BusinessException> { service.release(PLAN_ID, DRIVER_ID) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_NOT_FOUND)
    }

    private fun openPlan(): DeliveryPlan = DeliveryPlanFactory
        .createOpen(Location("서울 물류센터", 37.50, 126.90), LocalDateTime.now().plusHours(1))
        .also { ReflectionTestUtils.setField(it, "id", PLAN_ID) }

    private fun claimedPlan(owner: User): DeliveryPlan = openPlan().also { it.claim(owner) }

    /** 아직 추천 상위 기사만 수령할 수 있는 계획 */
    private fun priorityWindowPlan(): DeliveryPlan =
        openPlan().also { it.openPriorityWindow(LocalDateTime.now().plusMinutes(1)) }

    /** 우선권 구간이 끝나 전체 공개된 계획 */
    private fun publishedPlan(): DeliveryPlan =
        openPlan().also { it.openPriorityWindow(LocalDateTime.now().minusMinutes(1)) }

    private companion object {
        const val PLAN_ID = 10L
        const val DRIVER_ID = 7L
    }
}
