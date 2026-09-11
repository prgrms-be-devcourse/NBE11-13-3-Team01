package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.enums.RiskLevel

data class NextStopRecommendationResponse(
    val available: Boolean,
    val currentStopId: Long?,
    val recommendedStopId: Long?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val riskLevel: RiskLevel?,
    val riskScore: Int?,
    val candidateCount: Int,
    val candidateStopIds: List<Long>,
    val optimizedSafestRouteStopIds: List<Long>,
    val estimatedTravelSeconds: Long?,
    val kakaoTravelSeconds: Long?,
) {
    companion object {
        fun unavailable(currentStopId: Long?) = NextStopRecommendationResponse(
            false, currentStopId, null, null, null, null, null, null,
            0, emptyList(), emptyList(), null, null,
        )

        fun available(
            currentStopId: Long?,
            recommendedStop: DeliveryStop,
            candidateCount: Int,
            candidateStopIds: List<Long>,
            optimizedSafestRouteStopIds: List<Long>,
            estimatedTravelSeconds: Long,
            kakaoTravelSeconds: Long?,
        ) = NextStopRecommendationResponse(
            true,
            currentStopId,
            recommendedStop.id,
            recommendedStop.address,
            recommendedStop.latitude,
            recommendedStop.longitude,
            recommendedStop.riskAssessment.level,
            recommendedStop.riskAssessment.score,
            candidateCount,
            candidateStopIds.toList(),
            optimizedSafestRouteStopIds.toList(),
            estimatedTravelSeconds,
            kakaoTravelSeconds,
        )
    }
}
