package com.example.delivery_project.service

import com.example.delivery_project.config.DriverRecommendationProperties
import com.example.delivery_project.dto.response.DriverRecommendationResponse
import com.example.delivery_project.service.component.recommendation.AiDriverRecommendationEngine
import com.example.delivery_project.service.component.recommendation.AiRecommendationMode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation

@Service
class DriverRecommendationService(
    private val snapshotLoader: DriverRecommendationSnapshotLoader,
    private val recommendationEngine: AiDriverRecommendationEngine,
    private val recommendationProperties: DriverRecommendationProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun recommend(planId: Long, limit: Int?): DriverRecommendationResponse {
        val snapshot = snapshotLoader.load(planId)
        val recommendation = recommendationEngine.recommend(
            snapshot.context,
            (limit ?: recommendationProperties.limit).coerceIn(1, MAX_LIMIT),
            AiRecommendationMode.EXPLANATION_ONLY,
        )
        val scoredDrivers = recommendation.drivers

        log.info(
            "[RECOMMEND] 배송 기사 추천 완료 planId: {}, candidateSize: {}, excludedByLimit: {}, topDriverIds: {}",
            planId, snapshot.candidateCount, snapshot.excludedByClaimLimit, scoredDrivers.map { it.candidate.driverId },
        )

        return DriverRecommendationResponse.of(
            planId = planId,
            departureLocation = snapshot.departureLocation,
            scheduledDepartureAt = snapshot.scheduledDepartureAt,
            evaluatedAt = snapshot.evaluatedAt,
            candidateCount = snapshot.candidateCount,
            excludedByClaimLimit = snapshot.excludedByClaimLimit,
            scoredDrivers = scoredDrivers,
        )
    }

    private companion object {
        const val MAX_LIMIT = 10
    }
}
