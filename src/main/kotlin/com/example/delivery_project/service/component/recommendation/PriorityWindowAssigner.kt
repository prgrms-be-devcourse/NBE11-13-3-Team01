package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.config.PriorityWindowProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime

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
            if (priorityWindowProperties.deterministicFallback && outcome.drivers.isNotEmpty()) {
                log.warn(
                    "[PRIORITY] AI 추천 실패로 결정적 스코어 fallback을 적용한다 result={}, driverCount={}",
                    outcome.result.metricValue,
                    outcome.drivers.size,
                )
                return PriorityWindowSelection(outcome.drivers, outcome.result)
            }
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
