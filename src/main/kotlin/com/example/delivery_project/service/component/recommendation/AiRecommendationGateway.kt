package com.example.delivery_project.service.component.recommendation

import java.time.LocalDateTime

/**
 * n8n 과의 경계를 도메인 추천 로직에서 분리한다.
 *
 * 구현체는 예외를 밖으로 전파하지 않고 [AiGatewayResult.Failure]로 바꾼다. AI 추천은
 * 60초 우선권을 위한 부가 기능이므로 외부 장애가 업무 등록을 막아서는 안 된다.
 */
interface AiRecommendationGateway {
    fun recommend(request: AiRecommendationRequest): AiGatewayResult
}

data class AiRecommendationRequest(
    val requestId: String,
    val evaluatedAt: LocalDateTime,
    val target: AiRecommendationTarget,
    val requestedDriverCount: Int,
    val candidates: List<AiRecommendationCandidate>,
)

data class AiRecommendationTarget(
    val scheduledDepartureAt: LocalDateTime,
    val totalStops: Int,
    val totalBoxes: Long,
    val dangerStops: Long,
)

/** 이름·loginId·정확한 좌표를 포함하지 않는 n8n 전송 모델. */
data class AiRecommendationCandidate(
    val driverId: Long,
    val baseScore: Int,
    val distanceMeters: Long?,
    val estimatedTravelSeconds: Long?,
    val locationFresh: Boolean,
    val activePlans: Long,
    val remainingStops: Long,
    val remainingBoxes: Long,
    val dangerStops: Long,
    val features: List<AiRecommendationFeature>,
)

data class AiRecommendationFeature(
    val feature: String,
    val normalized: Double,
    val contribution: Double,
)

sealed interface AiGatewayResult {
    data class Success(val response: AiRecommendationResponse) : AiGatewayResult
    data class Failure(val result: AiRecommendationResult) : AiGatewayResult
}

data class AiRecommendationResponse(
    val requestId: String,
    val recommendations: List<AiRecommendedDriver>,
)

data class AiRecommendedDriver(
    val driverId: Long,
    val suitabilityScore: Int,
    val reasons: List<String>,
)

enum class AiRecommendationResult(val metricValue: String) {
    SUCCESS("success"),
    DISABLED("disabled"),
    NO_CANDIDATE("no_candidate"),
    TIMEOUT("timeout"),
    HTTP_ERROR("http_error"),
    CONFIG_ERROR("config_error"),
    INVALID_RESPONSE("invalid_response"),
    TRANSPORT_ERROR("transport_error"),
}
