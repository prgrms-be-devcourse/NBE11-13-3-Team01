package com.example.delivery_project.service

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.config.DriverRecommendationProperties
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.dto.response.DriverRecommendationResponse
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.recommendation.DriverCandidateLoader
import com.example.delivery_project.service.component.recommendation.DriverRecommender
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 아직 배정되지 않은 배송 업무에 어떤 기사가 적합한지 랭킹을 만들어 준다.
 *
 * 이 API 의 결과는 관리자용 참고 정보이고, 같은 스코어가 등록 시점에
 * [com.example.delivery_project.service.component.recommendation.PriorityWindowAssigner] 를 통해
 * 우선 수령 권한으로도 쓰인다. 실제 수령은 언제나 기사가 직접 claim 한다.
 */
@Service
@Transactional(readOnly = true)
class DriverRecommendationService(
    private val deliveryPlanRepository: DeliveryPlanRepository,
    private val driverCandidateLoader: DriverCandidateLoader,
    private val driverRecommender: DriverRecommender,
    private val recommendationProperties: DriverRecommendationProperties,
    private val claimProperties: DeliveryClaimProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun recommend(planId: Long, limit: Int?): DriverRecommendationResponse {
        val plan = deliveryPlanRepository.findDetailById(planId)
            ?: throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_FOUND)
        if (!plan.status.isOpen() && !plan.status.isReady()) {
            throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_ASSIGNABLE)
        }

        val evaluatedAt = LocalDateTime.now()
        val loaded = driverCandidateLoader.load(
            maxActivePlans = claimProperties.maxActivePlans,
            excludeDriverId = plan.driver?.id,
        )
        val scoredDrivers = driverRecommender.recommend(
            driverCandidateLoader.toContext(plan, loaded.candidates, evaluatedAt),
            (limit ?: recommendationProperties.limit).coerceIn(1, MAX_LIMIT),
        )

        log.info(
            "[RECOMMEND] 배송 기사 추천 완료 planId: {}, candidateSize: {}, excludedByLimit: {}, topDriverIds: {}",
            planId, loaded.candidates.size, loaded.excludedByClaimLimit, scoredDrivers.map { it.candidate.driverId },
        )

        return DriverRecommendationResponse.of(
            planId = planId,
            departureLocation = plan.departureLocation,
            scheduledDepartureAt = plan.scheduledDepartureAt,
            evaluatedAt = evaluatedAt,
            candidateCount = loaded.candidates.size,
            excludedByClaimLimit = loaded.excludedByClaimLimit,
            scoredDrivers = scoredDrivers,
        )
    }

    private companion object {
        const val MAX_LIMIT = 10
    }
}
