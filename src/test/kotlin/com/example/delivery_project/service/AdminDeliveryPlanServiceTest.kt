package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.DeliveryStopRepository
import com.example.delivery_project.domain.repository.RiskAssessmentRepository
import com.example.delivery_project.dto.projection.DeliveryPlanSummaryProjection
import com.example.delivery_project.dto.projection.DeliveryStatisticsProjection
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
class AdminDeliveryPlanServiceTest {
    @Mock lateinit var deliveryPlanRepository: DeliveryPlanRepository
    @Mock lateinit var deliveryStopRepository: DeliveryStopRepository
    @Mock lateinit var riskAssessmentRepository: RiskAssessmentRepository
    @Mock lateinit var planSummary: DeliveryPlanSummaryProjection
    @Mock lateinit var deliveryStatistics: DeliveryStatisticsProjection
    @InjectMocks lateinit var service: AdminDeliveryPlanService
    private lateinit var plan: DeliveryPlan

    @BeforeEach
    fun setUp() {
        val driver = User.of(7L, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)
        plan = DeliveryPlanFactory.create(
            driver, Location("서울 물류센터", 37.50, 126.90), LocalDateTime.now().plusHours(1),
        )
        ReflectionTestUtils.setField(plan, "id", 10L)
        plan.addStop("서울시청", 37.56, 126.97, LocalDateTime.now())
    }

    @Test
    fun 전체_배송계획에_담당기사_정보를_포함해_반환한다() {
        whenever(deliveryPlanRepository.findAllSummaries()).thenReturn(listOf(planSummary))
        whenever(planSummary.planId).thenReturn(10L)
        whenever(planSummary.driverId).thenReturn(7L)
        whenever(planSummary.driverLoginId).thenReturn("driver")
        whenever(planSummary.driverName).thenReturn("배송기사")
        whenever(planSummary.departureLocation).thenReturn("서울 물류센터")
        whenever(planSummary.scheduledDepartureAt).thenReturn(plan.scheduledDepartureAt)
        whenever(planSummary.status).thenReturn("READY")
        whenever(planSummary.totalStops).thenReturn(1L)
        whenever(planSummary.remainingStops).thenReturn(1L)
        whenever(planSummary.totalBoxes).thenReturn(0L)
        whenever(planSummary.remainingBoxes).thenReturn(0L)
        whenever(planSummary.dangerStops).thenReturn(0L)

        val responses = service.getAllDeliveryPlans()
        assertThat(responses).hasSize(1)
        val response = responses.single()
        assertThat(response.planId).isEqualTo(10L)
        assertThat(response.driverId).isEqualTo(7L)
        assertThat(response.driverLoginId).isEqualTo("driver")
        assertThat(response.driverName).isEqualTo("배송기사")
        assertThat(response.totalStops).isEqualTo(1)
        assertThat(response.totalBoxes).isZero()
        assertThat(response.remainingBoxes).isZero()
    }

    @Test
    fun 관리자는_소유자와_무관하게_배송계획_상세를_조회한다() {
        whenever(deliveryPlanRepository.findDetailById(10L)).thenReturn(plan)
        val response = service.getDeliveryPlan(10L)
        assertThat(response.driverId).isEqualTo(7L)
        assertThat(response.deliveryPlan.planId).isEqualTo(10L)
        assertThat(response.deliveryPlan.deliveryStops).hasSize(1)
    }

    @Test
    fun 존재하지_않는_배송계획은_조회할_수_없다() {
        whenever(deliveryPlanRepository.findDetailById(999L)).thenReturn(null)
        assertThat(assertThrows<BusinessException> { service.getDeliveryPlan(999L) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_NOT_FOUND)
    }

    @Test
    fun 배송계획_배송지_상품_위험_통계를_반환한다() {
        whenever(deliveryPlanRepository.getDeliveryStatistics()).thenReturn(deliveryStatistics)
        whenever(deliveryStatistics.totalPlans).thenReturn(10L)
        whenever(deliveryStatistics.openPlans).thenReturn(0L)
        whenever(deliveryStatistics.readyPlans).thenReturn(3L)
        whenever(deliveryStatistics.deliveringPlans).thenReturn(2L)
        whenever(deliveryStatistics.completedPlans).thenReturn(5L)
        whenever(deliveryStatistics.totalStops).thenReturn(30L)
        whenever(deliveryStatistics.remainingStops).thenReturn(8L)
        whenever(deliveryStatistics.totalBoxes).thenReturn(100L)
        whenever(deliveryStatistics.remainingBoxes).thenReturn(25L)
        whenever(deliveryStatistics.dangerStops).thenReturn(2L)

        val response = service.getDeliveryStatistics()

        assertThat(response.totalPlans).isEqualTo(10L)
        assertThat(response.completedStops).isEqualTo(22L)
        assertThat(response.deliveredBoxes).isEqualTo(75L)
        assertThat(response.dangerStops).isEqualTo(2L)
    }
}
