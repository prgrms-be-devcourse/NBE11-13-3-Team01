package com.example.delivery_project.domain.repository

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanPriorityDriver
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.dto.response.ClaimDeliveryPlanResponse
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.DeliveryPlanClaimService
import com.example.delivery_project.spec.Location
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

private const val MAX_ACTIVE_PLANS = 3
private const val CONTENDING_DRIVERS = 8
private val DEPARTURE = Location("서울 물류센터", 37.50, 126.90)

/**
 * 실제 MySQL 에서 배송 업무 수령 경합을 검증한다.
 *
 * ## 왜 `@Transactional(propagation = NOT_SUPPORTED)` 인가
 *
 * 테스트 메서드 전체를 하나의 트랜잭션으로 감싸면 스레드들이 커밋되지 않은 데이터를 보지 못해
 * 경합 자체가 재현되지 않는다. 테스트 래핑 트랜잭션을 끄고, 준비 데이터는 리포지토리 호출로 즉시 커밋시키며,
 * 각 스레드는 [DeliveryPlanClaimService] 의 `@Transactional` 로 독립 트랜잭션을 갖게 한다.
 * 롤백이 없으므로 [cleanUp] 에서 직접 정리한다.
 *
 * `TEST_DB_URL` 이 없으면 통째로 skip 된다. (H2 로는 InnoDB 행 잠금 동작을 재현할 수 없다)
 */
@DataJpaTest(
    properties = [
        "spring.sql.init.mode=never",
        "spring.datasource.hikari.maximum-pool-size=16",
    ],
)
@ActiveProfiles("db-test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeliveryPlanClaimConcurrencyTest {
    @TestConfiguration
    class ClaimTestConfiguration {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

        @Bean
        fun deliveryClaimProperties(): DeliveryClaimProperties =
            DeliveryClaimProperties().apply { maxActivePlans = MAX_ACTIVE_PLANS }

        @Bean
        fun deliveryPlanClaimService(
            deliveryPlanRepository: DeliveryPlanRepository,
            priorityDriverRepository: DeliveryPlanPriorityDriverRepository,
            userRepository: UserRepository,
            claimProperties: DeliveryClaimProperties,
            meterRegistry: MeterRegistry,
        ): DeliveryPlanClaimService = DeliveryPlanClaimService(
            deliveryPlanRepository, priorityDriverRepository, userRepository, claimProperties, meterRegistry,
        )
    }

    @Autowired
    private lateinit var claimService: DeliveryPlanClaimService

    @Autowired
    private lateinit var deliveryPlanRepository: DeliveryPlanRepository

    @Autowired
    private lateinit var priorityDriverRepository: DeliveryPlanPriorityDriverRepository

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var dataSource: DataSource

    private val jdbcTemplate: JdbcTemplate by lazy { JdbcTemplate(dataSource) }

    @AfterEach
    fun cleanUp() {
        listOf(
            "risk_factor", "risk_assessment", "delivery_item",
            "delivery_stop", "delivery_plan_priority_driver", "delivery_plan", "driver_location", "users",
        ).forEach { jdbcTemplate.execute("DELETE FROM $it") }
    }

    @Test
    fun `여러 기사가 같은 업무를 동시에 수령하면 한 명만 성공한다`() {
        val planId = saveOpenPlan()
        val driverIds = (1..CONTENDING_DRIVERS).map { saveDriver("rush-driver-$it") }

        val results = runConcurrently(driverIds.size) { index ->
            claimService.claim(planId, driverIds[index])
        }

        val succeeded = results.mapNotNull { it.getOrNull() }
        assertThat(succeeded).hasSize(1)
        assertThat(errorCodesOf(results))
            .containsOnly(DeliveryException.DELIVERY_PLAN_ALREADY_CLAIMED)
            .hasSize(driverIds.size - 1)

        val claimedPlan = reload(planId)
        assertThat(claimedPlan.status).isEqualTo(DeliveryPlanStatus.READY)
        assertThat(claimedPlan.driver?.id).isEqualTo(succeeded.single().driverId)
        assertThat(claimedPlan.assignedAt).isNotNull()
        // 네이티브 조건부 UPDATE 가 version 을 직접 증가시킨다.
        assertThat(claimedPlan.version).isEqualTo(1L)
    }

    @Test
    fun `같은 기사가 여러 업무를 동시에 수령해도 동시 보유 한도를 넘지 않는다`() {
        val driverId = saveDriver("limit-driver")
        val driver = requireNotNull(userRepository.findUserById(driverId))
        // 이미 2건 보유 → 한도 3건까지 1건만 더 가져갈 수 있어야 한다.
        repeat(MAX_ACTIVE_PLANS - 1) { saveAssignedPlan(driver) }
        val openPlanIds = (1..4).map { saveOpenPlan() }

        val results = runConcurrently(openPlanIds.size) { index ->
            claimService.claim(openPlanIds[index], driverId)
        }

        assertThat(results.mapNotNull { it.getOrNull() }).hasSize(1)
        assertThat(errorCodesOf(results))
            .containsOnly(DeliveryException.DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED)

        val activeCount = deliveryPlanRepository.countByDriverIdAndStatusIn(
            driverId, DeliveryPlanStatus.ACTIVE_STATUSES,
        )
        assertThat(activeCount).isEqualTo(MAX_ACTIVE_PLANS.toLong())
    }

    @Test
    fun `같은 기사의 동시 중복 수령 요청은 모두 성공하고 한 건만 실제 배정이다`() {
        val planId = saveOpenPlan()
        val driverId = saveDriver("retry-driver")

        val results = runConcurrently(4) { claimService.claim(planId, driverId) }

        assertThat(results.mapNotNull { it.exceptionOrNull() }).isEmpty()
        val responses = results.map { it.getOrThrow() }
        assertThat(responses.count { !it.alreadyOwned }).isEqualTo(1)
        assertThat(responses).allSatisfy { assertThat(it.driverId).isEqualTo(driverId) }
        assertThat(reload(planId).version).isEqualTo(1L)
    }

    @Test
    fun `반납하면 다시 미배정 상태로 돌아가 다른 기사가 수령할 수 있다`() {
        val planId = saveOpenPlan()
        val firstDriverId = saveDriver("release-driver-1")
        val secondDriverId = saveDriver("release-driver-2")

        claimService.claim(planId, firstDriverId)
        claimService.release(planId, firstDriverId)

        val released = reload(planId)
        assertThat(released.status).isEqualTo(DeliveryPlanStatus.OPEN)
        assertThat(released.driver).isNull()
        assertThat(released.assignedAt).isNull()
        assertThat(released.version).isEqualTo(2L)

        assertThat(claimService.claim(planId, secondDriverId).driverId).isEqualTo(secondDriverId)
    }

    @Test
    fun `우선 수령 구간에는 우선권을 가진 기사만 업무를 가져간다`() {
        val priorityDriverId = saveDriver("priority-driver")
        val outsiderIds = (1..3).map { saveDriver("outsider-$it") }
        val planId = saveOpenPlanWithPriorityWindow(priorityDriverId, windowSeconds = 120)

        // 우선권이 없는 기사들끼리 아무리 경합해도 아무도 가져갈 수 없다.
        val results = runConcurrently(outsiderIds.size) { index ->
            claimService.claim(planId, outsiderIds[index])
        }

        assertThat(results.mapNotNull { it.getOrNull() }).isEmpty()
        assertThat(errorCodesOf(results))
            .containsOnly(DeliveryException.DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE)
        assertThat(reload(planId).isClaimable).isTrue()

        // 같은 구간에서 우선권을 가진 기사는 바로 가져간다.
        assertThat(claimService.claim(planId, priorityDriverId).driverId).isEqualTo(priorityDriverId)
    }

    @Test
    fun `공개 시각이 지나면 우선권이 없는 기사도 선착순으로 경쟁한다`() {
        val priorityDriverId = saveDriver("expired-priority-driver")
        val outsiderIds = (1..3).map { saveDriver("late-outsider-$it") }
        // 이미 공개 시각이 지난 상태로 만든다.
        val planId = saveOpenPlanWithPriorityWindow(priorityDriverId, windowSeconds = -60)
        val contenders = outsiderIds + priorityDriverId

        val results = runConcurrently(contenders.size) { index ->
            claimService.claim(planId, contenders[index])
        }

        assertThat(results.mapNotNull { it.getOrNull() }).hasSize(1)
        assertThat(errorCodesOf(results))
            .containsOnly(DeliveryException.DELIVERY_PLAN_ALREADY_CLAIMED)
    }

    @Test
    fun `우선권을 가진 기사가 반납하면 우선권 없이 전체 공개된다`() {
        val priorityDriverId = saveDriver("release-priority-driver")
        val outsiderId = saveDriver("release-outsider")
        val planId = saveOpenPlanWithPriorityWindow(priorityDriverId, windowSeconds = 300)

        claimService.claim(planId, priorityDriverId)
        claimService.release(planId, priorityDriverId)

        assertThat(reload(planId).publicAt).isNull()
        // 윈도우가 사라졌으므로 우선권이 없는 기사도 즉시 가져갈 수 있다.
        assertThat(claimService.claim(planId, outsiderId).driverId).isEqualTo(outsiderId)
    }

    @Test
    fun `미배정 목록은 본인의 우선권 순위와 공개 시각을 함께 내려준다`() {
        val priorityDriverId = saveDriver("list-priority-driver")
        val outsiderId = saveDriver("list-outsider")
        val planId = saveOpenPlanWithPriorityWindow(priorityDriverId, windowSeconds = 300)

        val priorityView = claimService.getOpenPlans(priorityDriverId).single { it.planId == planId }
        assertThat(priorityView.priorityRank).isEqualTo(1)
        assertThat(priorityView.publicAt).isNotNull()
        assertThat(priorityView.claimableNow).isTrue()

        val outsiderView = claimService.getOpenPlans(outsiderId).single { it.planId == planId }
        assertThat(outsiderView.priorityRank).isNull()
        assertThat(outsiderView.publicAt).isNotNull()
        assertThat(outsiderView.claimableNow).isFalse()
        // 우선권 조인이 집계값을 왜곡하지 않는지 확인한다.
        assertThat(outsiderView.totalStops).isEqualTo(priorityView.totalStops)
    }

    @Test
    fun `미배정 목록 조회는 기사 정보가 없는 행을 null 로 매핑한다`() {
        val openPlanId = saveOpenPlan()
        val driver = requireNotNull(userRepository.findUserById(saveDriver("assigned-driver")))
        val assignedPlanId = saveAssignedPlan(driver)

        val viewerId = saveDriver("viewer-driver")
        val openSummaries = claimService.getOpenPlans(viewerId)

        assertThat(openSummaries.map { it.planId }).containsExactly(openPlanId)
        assertThat(openSummaries.single().priorityRank).isNull()
        assertThat(openSummaries.single().publicAt).isNull()
        assertThat(openSummaries.single().claimableNow).isTrue()
        // CAST(NULL AS UNSIGNED) / CAST(NULL AS CHAR) 가 Long? / String? 프로젝션으로 매핑되는지 확인한다.
        val summaryProjection = deliveryPlanRepository.findAllOpenSummaries().single()
        assertThat(summaryProjection.driverId).isNull()
        assertThat(summaryProjection.driverLoginId).isNull()
        assertThat(summaryProjection.driverName).isNull()
        assertThat(summaryProjection.assignedAt).isNull()
        assertThat(summaryProjection.totalStops.toLong()).isEqualTo(1L)

        // 관리자 전체 목록은 미배정 계획도 포함해야 한다. (users 를 LEFT JOIN 하는지 검증)
        assertThat(deliveryPlanRepository.findAllSummaries().map { it.planId })
            .contains(openPlanId, assignedPlanId)
    }

    private fun saveDriver(loginId: String): Long =
        requireNotNull(
            userRepository.save(User.of(loginId, "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)).id,
        )

    private fun saveOpenPlan(): Long = persistPlan(
        DeliveryPlanFactory.createOpen(DEPARTURE, LocalDateTime.now().plusHours(1)),
    )

    private fun saveAssignedPlan(driver: User): Long = persistPlan(
        DeliveryPlanFactory.create(driver, DEPARTURE, LocalDateTime.now().plusHours(1)),
    )

    private fun saveOpenPlanWithPriorityWindow(driverId: Long, windowSeconds: Long): Long {
        val plan = DeliveryPlanFactory.createOpen(DEPARTURE, LocalDateTime.now().plusHours(1))
        plan.openPriorityWindow(LocalDateTime.now().plusSeconds(windowSeconds))
        val planId = persistPlan(plan)
        val driver = requireNotNull(userRepository.findUserById(driverId))
        priorityDriverRepository.save(
            DeliveryPlanPriorityDriver.of(reload(planId), driver, 1, 90),
        )
        return planId
    }

    private fun persistPlan(plan: DeliveryPlan): Long {
        plan.addStop("배송지", 37.51, 126.91, LocalDateTime.now())
        return requireNotNull(deliveryPlanRepository.save(plan).id)
    }

    private fun reload(planId: Long): DeliveryPlan =
        requireNotNull(deliveryPlanRepository.findWithDriverById(planId))

    private fun errorCodesOf(results: List<Result<ClaimDeliveryPlanResponse>>) =
        results.mapNotNull { it.exceptionOrNull() }
            .map { (it as BusinessException).errorCode }

    /**
     * 모든 스레드를 같은 순간에 출발시켜 경합을 최대화한다.
     * 각 작업은 서비스의 `@Transactional` 을 통해 독립 트랜잭션에서 실행된다.
     */
    private fun runConcurrently(
        threadCount: Int,
        task: (Int) -> ClaimDeliveryPlanResponse,
    ): List<Result<ClaimDeliveryPlanResponse>> {
        val pool = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until threadCount).map { index ->
                pool.submit<Result<ClaimDeliveryPlanResponse>> {
                    ready.countDown()
                    start.await()
                    runCatching { task(index) }
                }
            }
            ready.await(10, TimeUnit.SECONDS)
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
