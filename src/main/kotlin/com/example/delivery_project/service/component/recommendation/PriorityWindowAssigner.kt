package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.config.PriorityWindowProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 저장 전 배송 계획에 대해 AI 우선 수령 대상을 계산한다.
 *
 * 선착순만 두면 알림을 먼저 본 기사가 가져가고, 반대로 관리자가 직접 배정하면 기사의 선택권이 사라진다.
 * 우선권 윈도우는 그 사이를 메운다.
 * 적합도가 높은 기사에게 먼저 기회를 주되 강제 배정은 하지 않고,
 * 아무도 가져가지 않으면 공개 시각 이후 전체가 선착순으로 경쟁한다.
 *
 * 이 컴포넌트는 DB 쓰기를 하지 않는다. n8n 호출이 끝난 뒤 별도의 짧은 저장 트랜잭션에서
 * 계획과 우선권 목록을 함께 저장하기 위해 선택 결과만 반환한다.
 */
@Component
class PriorityWindowAssigner(
    private val driverCandidateLoader: DriverCandidateLoader,
    private val recommendationEngine: AiDriverRecommendationEngine,
    private val priorityWindowProperties: PriorityWindowProperties,
    private val claimProperties: DeliveryClaimProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun select(plan: DeliveryPlan, evaluatedAt: LocalDateTime): PriorityWindowSelection {
        if (!priorityWindowProperties.enabled || priorityWindowProperties.driverCount <= 0) {
            return PriorityWindowSelection(emptyList(), AiRecommendationResult.DISABLED)
        }

        val loaded = driverCandidateLoader.load(maxActivePlans = claimProperties.maxActivePlans)
        if (loaded.candidates.isEmpty()) {
            log.info("[PRIORITY] 우선권 후보가 없어 즉시 전체 공개한다")
            return PriorityWindowSelection(emptyList(), AiRecommendationResult.NO_CANDIDATE)
        }

        val outcome = recommendationEngine.recommend(
            driverCandidateLoader.toContext(plan, loaded.candidates, evaluatedAt),
            priorityWindowProperties.driverCount,
        )
        if (!outcome.aiApplied) {
            log.info("[PRIORITY] AI 추천을 적용하지 못해 즉시 전체 공개한다 result={}", outcome.result.metricValue)
            return PriorityWindowSelection(emptyList(), outcome.result)
        }

        return PriorityWindowSelection(outcome.drivers, AiRecommendationResult.SUCCESS)
    }
}

data class PriorityWindowSelection(
    val drivers: List<ScoredDriver>,
    val result: AiRecommendationResult,
)
