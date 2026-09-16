package com.example.delivery_project.service

import com.example.delivery_project.config.DriverRecommendationProperties
import com.example.delivery_project.dto.response.DriverRecommendationResponse
import com.example.delivery_project.service.component.recommendation.AiDriverRecommendationEngine
import com.example.delivery_project.service.component.recommendation.AiRecommendationMode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Propagation

/**
 * 아직 배정되지 않은 배송 업무에 어떤 기사가 적합한지 랭킹을 만들어 준다.
 *
 * 이 API 의 결과는 관리자용 참고 정보이고, 같은 스코어가 등록 시점에
 * [com.example.delivery_project.service.component.recommendation.PriorityWindowAssigner] 를 통해
 * 우선 수령 권한으로도 쓰인다. 실제 수령은 언제나 기사가 직접 claim 한다.
 */
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
