package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.DeliveryStopRepository
import com.example.delivery_project.domain.repository.RiskAssessmentRepository
import com.example.delivery_project.dto.projection.DeliveryPlanSummaryProjection
import com.example.delivery_project.dto.request.UpdateDeliveryOrderRequest
import com.example.delivery_project.dto.request.UpdateScheduledDepartureRequest
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.ProductType
import com.example.delivery_project.enums.RiskLevel
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.spec.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class DeliveryPlanServiceTest {
    @Mock lateinit var deliveryPlanRepository: DeliveryPlanRepository
    @Mock lateinit var deliveryStopRepository: DeliveryStopRepository
    @Mock lateinit var riskAssessmentRepository: RiskAssessmentRepository
    @Mock lateinit var planSummary: DeliveryPlanSummaryProjection
    @InjectMocks lateinit var service: DeliveryPlanService
    private lateinit var plan: DeliveryPlan
    private lateinit var firstStop: DeliveryStop
    private lateinit var secondStop: DeliveryStop

    @BeforeEach
    fun setUp() {
        val driver = User.of(1L, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)
        plan = DeliveryPlanFactory.create(
            driver, Location("서울 물류센터", 37.50, 126.90), LocalDateTime.now().plusHours(1),
        )
        ReflectionTestUtils.setField(plan, "id", 10L)
        firstStop = plan.addStop("배송지 1", 37.51, 126.91, LocalDateTime.now())
        secondStop = plan.addStop("배송지 2", 37.52, 126.92, LocalDateTime.now())
        ReflectionTestUtils.setField(firstStop, "id", 101L)
        ReflectionTestUtils.setField(secondStop, "id", 102L)
        firstStop.addItem("상품", ProductType.NORMAL, 2)
    }

    @Test
    fun 기사별_배송계획_목록을_요약_응답으로_반환한다() {
        whenever(deliveryPlanRepository.findAllSummariesByDriverId(1L)).thenReturn(listOf(planSummary))
        whenever(planSummary.planId).thenReturn(10L)
        whenever(planSummary.departureLocation).thenReturn("서울 물류센터")
        whenever(planSummary.scheduledDepartureAt).thenReturn(plan.scheduledDepartureAt)
        whenever(planSummary.status).thenReturn("READY")
        whenever(planSummary.totalStops).thenReturn(2L)
        whenever(planSummary.remainingStops).thenReturn(2L)
        whenever(planSummary.totalBoxes).thenReturn(2L)
        whenever(planSummary.remainingBoxes).thenReturn(2L)
        whenever(planSummary.dangerStops).thenReturn(0L)
        val responses = service.getDeliveryPlans(1L)
        assertThat(responses).hasSize(1)
        val response = responses.first()
        assertThat(response.planId).isEqualTo(10L)
        assertThat(response.totalStops).isEqualTo(2)
        assertThat(response.remainingStops).isEqualTo(2)
        assertThat(response.totalBoxes).isEqualTo(2)
        assertThat(response.remainingBoxes).isEqualTo(2)
    }

    @Test
    fun 배송계획과_배송지_상세를_응답으로_변환한다() {
        whenever(deliveryPlanRepository.findWithStopsAndRiskByIdAndDriverId(10L, 1L)).thenReturn(plan)
        whenever(deliveryPlanRepository.findByIdAndDriverId(10L, 1L)).thenReturn(plan)
        whenever(deliveryStopRepository.findDetailByIdAndPlanId(101L, 10L)).thenReturn(firstStop)
        val planResponse = service.getDeliveryPlan(10L, 1L)
        val stopResponse = service.getDeliveryStop(10L, 101L, 1L)
        assertThat(planResponse.planId).isEqualTo(10L)
        assertThat(planResponse.deliveryStops).hasSize(2)
        assertThat(stopResponse.stopId).isEqualTo(101L)
        assertThat(stopResponse.deliveryItems).hasSize(1)
        val risk = requireNotNull(stopResponse.riskAssessment)
        assertThat(risk.score).isEqualTo(-1)
        assertThat(risk.level).isEqualTo(RiskLevel.UNKNOWN)
        assertThat(risk.factors).isEmpty()
    }

    @Test
    fun 배송_상태_변경_흐름을_서비스에서_수행한다() {
        whenever(deliveryPlanRepository.findByIdAndDriverId(10L, 1L)).thenReturn(plan)
        whenever(deliveryPlanRepository.findWithStopsAndRiskByIdAndDriverId(10L, 1L)).thenReturn(plan)
        val changedDepartureAt = LocalDateTime.now().plusHours(2)
        service.changeScheduledDepartureAt(10L, 1L, UpdateScheduledDepartureRequest(changedDepartureAt))
        service.reorderStops(10L, 1L, UpdateDeliveryOrderRequest(listOf(102L, 101L)))
        service.start(10L, 1L)
        service.completeStop(10L, 101L, 1L)
        service.completeStop(10L, 102L, 1L)
        service.completePlan(10L, 1L)
        assertThat(plan.scheduledDepartureAt).isEqualTo(changedDepartureAt)
        assertThat(plan.deliveryStops.map { it.id }).containsExactly(102L, 101L)
        assertThat(plan.status).isEqualTo(DeliveryPlanStatus.COMPLETED)
    }

    @Test
    fun 존재하지_않는_계획을_조회하면_예외가_발생한다() {
        whenever(deliveryPlanRepository.findWithStopsAndRiskByIdAndDriverId(999L, 1L)).thenReturn(null)
        assertThat(assertThrows<BusinessException> { service.getDeliveryPlan(999L, 1L) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_NOT_FOUND)
    }

    @Test
    fun 계획에_속한_배송지가_없으면_예외가_발생한다() {
        whenever(deliveryPlanRepository.findByIdAndDriverId(10L, 1L)).thenReturn(plan)
        whenever(deliveryStopRepository.findDetailByIdAndPlanId(999L, 10L)).thenReturn(null)
        assertThat(assertThrows<BusinessException> { service.getDeliveryStop(10L, 999L, 1L) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_STOP_NOT_FOUND)
    }

    @Test
    fun 다른_기사의_배송계획에는_접근할_수_없다() {
        whenever(deliveryPlanRepository.findWithStopsAndRiskByIdAndDriverId(10L, 2L)).thenReturn(null)
        assertThat(assertThrows<BusinessException> { service.getDeliveryPlan(10L, 2L) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_NOT_FOUND)
    }
}
