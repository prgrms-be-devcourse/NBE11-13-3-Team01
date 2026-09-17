package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DriverRecommendationProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

class AiDriverRecommendationEngineTest {
    private val gateway = mock<AiRecommendationGateway>()
    private val properties = DriverRecommendationProperties()
    private val meterRegistry = SimpleMeterRegistry()
    private lateinit var engine: AiDriverRecommendationEngine

    @BeforeEach
    fun setUp() {
        properties.ai.enabled = true
        properties.ai.candidatePoolSize = 10
        engine = AiDriverRecommendationEngine(
            ScoreBasedDriverRecommender(properties),
            gateway,
            properties,
            meterRegistry,
        )
    }

    @Test
    fun `유효한 AI 응답은 순위와 점수를 적용하고 기준 점수는 보존한다`() {
        whenever(gateway.recommend(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<AiRecommendationRequest>(0)
            AiGatewayResult.Success(
                AiRecommendationResponse(
                    requestId = request.requestId,
                    recommendations = listOf(
                        AiRecommendedDriver(2L, 96, listOf("배송지 접근 경험과 현재 업무량이 적합합니다")),
                        AiRecommendedDriver(1L, 84, listOf("현재 위치와 출발지의 접근성이 양호합니다")),
                    ),
                ),
            )
        }

        val outcome = engine.recommend(context(), 2)

        assertThat(outcome.aiApplied).isTrue()
        assertThat(outcome.result).isEqualTo(AiRecommendationResult.SUCCESS)
        assertThat(outcome.drivers.map { it.candidate.driverId }).containsExactly(2L, 1L)
        assertThat(outcome.drivers.map { it.score }).containsExactly(96, 84)
        assertThat(outcome.drivers).allSatisfy { assertThat(it.baseScore).isNotNull() }

        val requestCaptor = argumentCaptor<AiRecommendationRequest>()
        verify(gateway).recommend(requestCaptor.capture())
        val request = requestCaptor.firstValue
        assertThat(request.candidates.map { it.driverId }).containsExactlyInAnyOrder(1L, 2L)
        assertThat(request.toString()).doesNotContain("기사1", "기사2", "driver1", "driver2", "37.5", "126.9")
    }

    @Test
    fun `AI 타임아웃이면 결정적 추천만 반환하고 AI 적용 여부를 false로 둔다`() {
        whenever(gateway.recommend(any())).thenReturn(AiGatewayResult.Failure(AiRecommendationResult.TIMEOUT))

        val outcome = engine.recommend(context(), 2)

        assertThat(outcome.aiApplied).isFalse()
        assertThat(outcome.result).isEqualTo(AiRecommendationResult.TIMEOUT)
        assertThat(outcome.drivers).hasSize(2)
        assertThat(outcome.drivers).allSatisfy { assertThat(it.baseScore).isNull() }
    }

    @Test
    fun `후보에 없는 기사나 일부 기사만 반환하면 응답 전체를 폐기한다`() {
        whenever(gateway.recommend(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<AiRecommendationRequest>(0)
            AiGatewayResult.Success(
                AiRecommendationResponse(
                    requestId = request.requestId,
                    recommendations = listOf(AiRecommendedDriver(999L, 100, listOf("알 수 없는 기사"))),
                ),
            )
        }

        val outcome = engine.recommend(context(), 2)

        assertThat(outcome.aiApplied).isFalse()
        assertThat(outcome.result).isEqualTo(AiRecommendationResult.INVALID_RESPONSE)
        assertThat(outcome.drivers.map { it.candidate.driverId }).containsExactlyInAnyOrder(1L, 2L)
    }

    @Test
    fun `중복 driverId 응답은 invalid response로 폐기한다`() {
        whenever(gateway.recommend(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<AiRecommendationRequest>(0)
            AiGatewayResult.Success(
                AiRecommendationResponse(
                    requestId = request.requestId,
                    recommendations = listOf(
                        AiRecommendedDriver(1L, 90, listOf("첫 번째 설명")),
                        AiRecommendedDriver(1L, 80, listOf("중복 설명")),
                    ),
                ),
            )
        }

        val outcome = engine.recommend(context(), 2)

        assertThat(outcome.aiApplied).isFalse()
        assertThat(outcome.result).isEqualTo(AiRecommendationResult.INVALID_RESPONSE)
    }

    @Test
    fun `AI 기능이 꺼져 있으면 외부 호출 없이 결정적 추천을 반환한다`() {
        properties.ai.enabled = false

        val outcome = engine.recommend(context(), 2)

        assertThat(outcome.aiApplied).isFalse()
        assertThat(outcome.result).isEqualTo(AiRecommendationResult.DISABLED)
        verify(gateway, never()).recommend(any())
    }

    @Test
    fun `설명 전용 모드에서는 AI 응답 순서와 점수를 무시하고 기준 순위를 유지한다`() {
        whenever(gateway.recommend(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<AiRecommendationRequest>(0)
            AiGatewayResult.Success(
                AiRecommendationResponse(
                    requestId = request.requestId,
                    recommendations = listOf(
                        AiRecommendedDriver(2L, 1, listOf("AI가 만든 설명")),
                        AiRecommendedDriver(1L, 100, listOf("다른 설명")),
                    ),
                ),
            )
        }

        val baseline = ScoreBasedDriverRecommender(properties).recommend(context(), 2)
        val outcome = engine.recommend(context(), 2, AiRecommendationMode.EXPLANATION_ONLY)

        assertThat(outcome.aiApplied).isFalse()
        assertThat(outcome.drivers.map { it.candidate.driverId })
            .containsExactlyElementsOf(baseline.map { it.candidate.driverId })
        assertThat(outcome.drivers.map { it.score }).containsExactlyElementsOf(baseline.map { it.score })
        assertThat(outcome.drivers.map { it.reasons }).containsExactly(
            listOf("다른 설명"), listOf("AI가 만든 설명"),
        )
    }

    private fun context() = DriverRecommendationContext(
        target = RecommendationTarget(
            planId = null,
            departureLocation = "서울 물류센터",
            departureLatitude = 37.5,
            departureLongitude = 126.9,
            scheduledDepartureAt = NOW.plusHours(1),
            totalStops = 3,
            totalBoxes = 10,
            dangerStops = 0,
        ),
        candidates = listOf(candidate(1L), candidate(2L)),
        evaluatedAt = NOW,
    )

    private fun candidate(id: Long) = DriverCandidate(
        driverId = id,
        loginId = "driver$id",
        name = "기사$id",
        activePlans = 0,
        remainingStops = 0,
        remainingBoxes = 0,
        dangerStops = 0,
        latitude = 37.5,
        longitude = 126.9,
        locationUpdatedAt = NOW,
    )

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 16, 12, 0)
    }
}
