package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.RiskAssessmentRepository
import com.example.delivery_project.enums.RiskFactorType
import com.example.delivery_project.enums.RiskLevel
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.DrivingDirectionsClient
import com.example.delivery_project.service.component.route.DijkstraRouteOptimizer
import com.example.delivery_project.spec.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class NextStopRecommendationServiceTest {
    @Mock lateinit var deliveryPlanRepository: DeliveryPlanRepository
    @Mock lateinit var riskAssessmentRepository: RiskAssessmentRepository
    @Mock lateinit var drivingDirectionsClient: DrivingDirectionsClient
    private lateinit var service: NextStopRecommendationService
    private lateinit var plan: DeliveryPlan

    @BeforeEach
    fun setUp() {
        service = NextStopRecommendationService(
            deliveryPlanRepository, riskAssessmentRepository, DijkstraRouteOptimizer(), drivingDirectionsClient,
        )
        val driver = User.of(1L, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)
        plan = DeliveryPlanFactory.create(
            driver, Location("서울 물류센터", 37.50, 126.90), LocalDateTime.now().plusHours(1),
        )
        ReflectionTestUtils.setField(plan, "id", 10L)
    }

    @Test
    fun 완료한_배송지_다음_최대_5곳에서_위험도가_가장_낮은_곳을_추천한다() {
        val first = addStop(101L, 37.501, 126.901)
        val second = addStop(102L, 37.502, 126.902)
        val third = addStop(103L, 37.503, 126.903)
        val fourth = addStop(104L, 37.504, 126.904)
        val fifth = addStop(105L, 37.505, 126.905)
        val sixth = addStop(106L, 37.506, 126.906)
        val seventh = addStop(107L, 37.5001, 126.9001)
        markKnown(second, listOf(RiskFactorType.HEAVY_RAIN))
        markKnown(third, listOf(RiskFactorType.HEAT_WAVE))
        markKnown(fourth, emptyList())
        markKnown(fifth, listOf(RiskFactorType.WEATHER_WARNING))
        markKnown(sixth, listOf(RiskFactorType.HEAVY_RAIN, RiskFactorType.HEAT_WAVE))
        markKnown(seventh, emptyList())
        plan.start()
        plan.completeStop(requireNotNull(first.id))
        whenever(deliveryPlanRepository.findWithStopsAndRiskByIdAndDriverId(10L, 1L)).thenReturn(plan)
        whenever(drivingDirectionsClient.findTravelDurationSeconds(37.501, 126.901, 37.504, 126.904))
            .thenReturn(1_125L)

        val response = service.recommend(10L, 1L)
        assertThat(response.available).isTrue()
        assertThat(response.currentStopId).isEqualTo(101L)
        assertThat(response.candidateStopIds).containsExactly(102L, 103L, 104L, 105L, 106L)
        assertThat(response.candidateCount).isEqualTo(5)
        assertThat(response.recommendedStopId).isEqualTo(104L)
        assertThat(response.riskLevel).isEqualTo(RiskLevel.SAFE)
        assertThat(response.riskScore).isZero()
        assertThat(response.optimizedSafestRouteStopIds).containsExactly(104L)
        assertThat(response.estimatedTravelSeconds).isPositive()
        assertThat(response.kakaoTravelSeconds).isEqualTo(1_125L)
        verify(riskAssessmentRepository).findAllWithFactorsByDeliveryStopIdIn(listOf(102L, 103L, 104L, 105L, 106L))
    }

    @Test
    fun 위험도가_같으면_다익스트라_경로의_첫_배송지를_추천한다() {
        val first = addStop(101L, 37.50, 126.90)
        val farStop = addStop(102L, 37.60, 127.00)
        val nearStop = addStop(103L, 37.51, 126.91)
        markKnown(farStop, emptyList())
        markKnown(nearStop, emptyList())
        plan.start()
        plan.completeStop(requireNotNull(first.id))
        whenever(deliveryPlanRepository.findWithStopsAndRiskByIdAndDriverId(10L, 1L)).thenReturn(plan)
        whenever(drivingDirectionsClient.findTravelDurationSeconds(37.50, 126.90, 37.51, 126.91)).thenReturn(null)
        val response = service.recommend(10L, 1L)
        assertThat(response.recommendedStopId).isEqualTo(103L)
        assertThat(response.optimizedSafestRouteStopIds).containsExactly(103L, 102L)
        assertThat(response.estimatedTravelSeconds).isPositive()
        assertThat(response.kakaoTravelSeconds).isNull()
    }

    @Test
    fun 위험도_미확인은_점수_마이너스여도_안전한_배송지보다_우선하지_않는다() {
        val first = addStop(101L, 37.50, 126.90)
        val unknownStop = addStop(102L, 37.5001, 126.9001)
        val safeStop = addStop(103L, 37.52, 126.92)
        markKnown(safeStop, emptyList())
        plan.start()
        plan.completeStop(requireNotNull(first.id))
        whenever(deliveryPlanRepository.findWithStopsAndRiskByIdAndDriverId(10L, 1L)).thenReturn(plan)
        val response = service.recommend(10L, 1L)
        assertThat(unknownStop.riskAssessment.score).isEqualTo(-1)
        assertThat(response.recommendedStopId).isEqualTo(103L)
    }

    @Test
    fun 배송중이_아니면_추천할_수_없다() {
        addStop(101L, 37.50, 126.90)
        whenever(deliveryPlanRepository.findWithStopsAndRiskByIdAndDriverId(10L, 1L)).thenReturn(plan)
        assertThat(assertThrows<BusinessException> { service.recommend(10L, 1L) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_RECOMMENDATION_NOT_AVAILABLE)
    }

    private fun addStop(id: Long, latitude: Double, longitude: Double): DeliveryStop =
        plan.addStop("배송지 $id", latitude, longitude, LocalDateTime.now()).also {
            ReflectionTestUtils.setField(it, "id", id)
        }

    private fun markKnown(stop: DeliveryStop, factors: List<RiskFactorType>) {
        stop.riskAssessment.replaceFactors(factors, LocalDateTime.now())
    }
}
