package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DriverRecommendationProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.math.roundToLong

@Component
class AiDriverRecommendationEngine(
    private val deterministicRecommender: ScoreBasedDriverRecommender,
    private val gateway: AiRecommendationGateway,
    private val properties: DriverRecommendationProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun recommend(
        context: DriverRecommendationContext,
        limit: Int,
        mode: AiRecommendationMode = AiRecommendationMode.PRIORITY_SELECTION,
    ): AiRecommendationOutcome {
        val requestedCount = limit.coerceAtLeast(1)
        val poolSize = properties.ai.candidatePoolSize.coerceAtLeast(requestedCount)
        val baseline = deterministicRecommender.recommend(context, poolSize)
        val fallback = baseline.take(requestedCount)

        if (baseline.isEmpty()) {
            record(AiRecommendationResult.NO_CANDIDATE)
            return AiRecommendationOutcome(fallback, false, AiRecommendationResult.NO_CANDIDATE)
        }
        if (!properties.ai.enabled) {
            record(AiRecommendationResult.DISABLED)
            return AiRecommendationOutcome(fallback, false, AiRecommendationResult.DISABLED)
        }

        val requestCount = requestedCount.coerceAtMost(baseline.size)
        val requestBaseline = if (mode == AiRecommendationMode.EXPLANATION_ONLY) {
            baseline.take(requestCount)
        } else {
            baseline
        }
        val request = requestOf(context, requestBaseline, requestCount)
        return when (val gatewayResult = gateway.recommend(request)) {
            is AiGatewayResult.Failure -> {
                record(gatewayResult.result)
                AiRecommendationOutcome(fallback, false, gatewayResult.result)
            }
            is AiGatewayResult.Success -> applyValidated(request, requestBaseline, fallback, gatewayResult.response, mode)
        }
    }

    private fun applyValidated(
        request: AiRecommendationRequest,
        baseline: List<ScoredDriver>,
        fallback: List<ScoredDriver>,
        response: AiRecommendationResponse,
        mode: AiRecommendationMode,
    ): AiRecommendationOutcome {
        val recommendations = response.recommendations
        val expectedCount = request.requestedDriverCount
        val baselineById = baseline.associateBy { it.candidate.driverId }
        val valid = response.requestId == request.requestId &&
            recommendations.size == expectedCount &&
            recommendations.map { it.driverId }.distinct().size == recommendations.size &&
            recommendations.all { recommended ->
                recommended.driverId in baselineById &&
                    recommended.suitabilityScore in 0..100 &&
                    validReasons(recommended.reasons)
            }

        if (!valid) {
            log.warn("[AI-RECOMMEND] n8n 응답 계약 위반 requestId={}", request.requestId)
            record(AiRecommendationResult.INVALID_RESPONSE)
            return AiRecommendationOutcome(fallback, false, AiRecommendationResult.INVALID_RESPONSE)
        }

        if (mode == AiRecommendationMode.EXPLANATION_ONLY) {
            val reasonsById = recommendations.associate { it.driverId to it.reasons.map(String::trim) }
            record(AiRecommendationResult.SUCCESS)
            return AiRecommendationOutcome(
                fallback.map { base -> base.copy(reasons = reasonsById[base.candidate.driverId] ?: base.reasons) },
                aiApplied = false,
                result = AiRecommendationResult.SUCCESS,
            )
        }

        val ranked = recommendations.map { recommended ->
            val base = requireNotNull(baselineById[recommended.driverId])
            base.copy(
                score = recommended.suitabilityScore,
                baseScore = base.score,
                reasons = recommended.reasons.map(String::trim),
            )
        }
        record(AiRecommendationResult.SUCCESS)
        return AiRecommendationOutcome(ranked, true, AiRecommendationResult.SUCCESS)
    }

    private fun validReasons(reasons: List<String>): Boolean =
        reasons.isNotEmpty() &&
            reasons.size <= properties.ai.maxReasonsPerDriver &&
            reasons.all { reason ->
                val trimmed = reason.trim()
                trimmed.isNotEmpty() &&
                    trimmed.length <= properties.ai.maxReasonLength &&
                    trimmed.none(Char::isISOControl)
            }

    private fun requestOf(
        context: DriverRecommendationContext,
        baseline: List<ScoredDriver>,
        requestedCount: Int,
    ) = AiRecommendationRequest(
        requestId = UUID.randomUUID().toString(),
        evaluatedAt = context.evaluatedAt,
        target = AiRecommendationTarget(
            scheduledDepartureAt = context.target.scheduledDepartureAt,
            totalStops = context.target.totalStops,
            totalBoxes = context.target.totalBoxes,
            dangerStops = context.target.dangerStops,
        ),
        requestedDriverCount = requestedCount,
        candidates = baseline.map { scored ->
            AiRecommendationCandidate(
                driverId = scored.candidate.driverId,
                baseScore = scored.score,
                distanceMeters = scored.distanceMeters?.roundToLong(),
                estimatedTravelSeconds = scored.estimatedTravelSeconds,
                locationFresh = scored.locationFresh,
                activePlans = scored.candidate.activePlans,
                remainingStops = scored.candidate.remainingStops,
                remainingBoxes = scored.candidate.remainingBoxes,
                dangerStops = scored.candidate.dangerStops,
                features = scored.featureScores.map { feature ->
                    AiRecommendationFeature(feature.feature, feature.normalized, feature.contribution)
                },
            )
        },
    )

    private fun record(result: AiRecommendationResult) {
        meterRegistry.counter(METRIC_NAME, "result", result.metricValue).increment()
    }

    private companion object {
        const val METRIC_NAME = "delivery_ai_recommendation_decision_total"
    }
}

data class AiRecommendationOutcome(
    val drivers: List<ScoredDriver>,
    val aiApplied: Boolean,
    val result: AiRecommendationResult,
)

enum class AiRecommendationMode {
    PRIORITY_SELECTION,

    EXPLANATION_ONLY,
}
