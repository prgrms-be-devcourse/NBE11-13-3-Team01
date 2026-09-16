package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.config.PriorityWindowProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.spec.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

class PriorityWindowAssignerTest {
    private val driverCandidateLoader = mock<DriverCandidateLoader>()
    private val recommendationEngine = mock<AiDriverRecommendationEngine>()
    private val priorityWindowProperties = PriorityWindowProperties()
    private val claimProperties = DeliveryClaimProperties()
    private lateinit var assigner: PriorityWindowAssigner

    @BeforeEach
    fun setUp() {
        priorityWindowProperties.enabled = true
        priorityWindowProperties.seconds = 60
        priorityWindowProperties.driverCount = 2
        assigner = PriorityWindowAssigner(
            driverCandidateLoader,
            recommendationEngine,
            priorityWindowProperties,
            claimProperties,
        )
    }

    @Test
    fun `AI 추천 성공 결과를 저장 단계에 전달한다`() {
        val plan = openPlan()
        whenever(driverCandidateLoader.load(any(), anyOrNull()))
            .thenReturn(DriverCandidates(listOf(candidateOf(1L), candidateOf(2L)), 0))
        whenever(driverCandidateLoader.toContext(any(), any(), any())).thenReturn(contextOf(plan))
        whenever(recommendationEngine.recommend(any(), eq(2), eq(AiRecommendationMode.PRIORITY_SELECTION))).thenReturn(
            AiRecommendationOutcome(
                listOf(scored(1L, 95), scored(2L, 80)),
                aiApplied = true,
                result = AiRecommendationResult.SUCCESS,
            ),
        )

        val selection = assigner.select(plan, NOW)

        assertThat(selection.result).isEqualTo(AiRecommendationResult.SUCCESS)
        assertThat(selection.drivers.map { it.candidate.driverId }).containsExactly(1L, 2L)
        assertThat(plan.publicAt).isNull()
    }

    @Test
    fun `윈도우를 끄면 우선권을 부여하지 않고 즉시 전체 공개한다`() {
        priorityWindowProperties.enabled = false
        val plan = openPlan()

        assertThat(assigner.select(plan, NOW).drivers).isEmpty()

        assertThat(plan.publicAt).isNull()
        verify(recommendationEngine, never()).recommend(any(), any(), any())
    }

    @Test
    fun `수령 가능한 기사가 없으면 우선권 없이 즉시 전체 공개한다`() {
        val plan = openPlan()
        whenever(driverCandidateLoader.load(any(), anyOrNull())).thenReturn(DriverCandidates(emptyList(), 3))

        assertThat(assigner.select(plan, NOW).drivers).isEmpty()

        assertThat(plan.publicAt).isNull()
        verify(recommendationEngine, never()).recommend(any(), any(), any())
    }

    @Test
    fun `AI 추천 실패면 결정적 fallback을 우선권에 쓰지 않고 즉시 전체 공개한다`() {
        val plan = openPlan()
        whenever(driverCandidateLoader.load(any(), anyOrNull()))
            .thenReturn(DriverCandidates(listOf(candidateOf(1L)), 0))
        whenever(driverCandidateLoader.toContext(any(), any(), any())).thenReturn(contextOf(plan))
        whenever(recommendationEngine.recommend(any(), eq(2), eq(AiRecommendationMode.PRIORITY_SELECTION))).thenReturn(
            AiRecommendationOutcome(
                listOf(scored(1L, 90)),
                aiApplied = false,
                result = AiRecommendationResult.TIMEOUT,
            ),
        )

        val selection = assigner.select(plan, NOW)

        assertThat(selection.drivers).isEmpty()
        assertThat(selection.result).isEqualTo(AiRecommendationResult.TIMEOUT)
        assertThat(plan.publicAt).isNull()
    }

    @Test
    fun `데모 fallback을 켜면 AI 실패 시 결정적 추천을 우선권에 전달한다`() {
        priorityWindowProperties.deterministicFallback = true
        val plan = openPlan()
        whenever(driverCandidateLoader.load(any(), anyOrNull()))
            .thenReturn(DriverCandidates(listOf(candidateOf(1L)), 0))
        whenever(driverCandidateLoader.toContext(any(), any(), any())).thenReturn(contextOf(plan))
        whenever(recommendationEngine.recommend(any(), eq(2), eq(AiRecommendationMode.PRIORITY_SELECTION))).thenReturn(
            AiRecommendationOutcome(
                listOf(scored(1L, 90)),
                aiApplied = false,
                result = AiRecommendationResult.DISABLED,
            ),
        )

        val selection = assigner.select(plan, NOW)

        assertThat(selection.drivers.map { it.candidate.driverId }).containsExactly(1L)
        assertThat(selection.result).isEqualTo(AiRecommendationResult.DISABLED)
    }

    private fun openPlan(): DeliveryPlan = DeliveryPlanFactory
        .createOpen(Location("서울 물류센터", 37.50, 126.90), NOW.plusHours(1))
        .also { ReflectionTestUtils.setField(it, "id", 10L) }

    private fun candidateOf(driverId: Long) = DriverCandidate(
        driverId = driverId,
        loginId = "driver$driverId",
        name = "기사$driverId",
        activePlans = 0,
        remainingStops = 0,
        remainingBoxes = 0,
        dangerStops = 0,
        latitude = 37.50,
        longitude = 126.90,
        locationUpdatedAt = NOW,
    )

    private fun scored(driverId: Long, score: Int) = ScoredDriver(
        candidate = candidateOf(driverId),
        score = score,
        distanceMeters = 0.0,
        estimatedTravelSeconds = 1L,
        locationFresh = true,
        featureScores = emptyList(),
        reasons = emptyList(),
    )

    private fun contextOf(plan: DeliveryPlan) = DriverRecommendationContext(
        target = RecommendationTarget(
            planId = requireNotNull(plan.id),
            departureLocation = plan.departureLocation,
            departureLatitude = plan.departureLatitude,
            departureLongitude = plan.departureLongitude,
            scheduledDepartureAt = plan.scheduledDepartureAt,
            totalStops = plan.totalStops,
            totalBoxes = plan.totalBoxes,
            dangerStops = plan.dangerStops,
        ),
        candidates = emptyList(),
        evaluatedAt = NOW,
    )

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 14, 12, 0, 0)
    }
}
