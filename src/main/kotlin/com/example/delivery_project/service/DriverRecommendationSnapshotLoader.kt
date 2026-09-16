package com.example.delivery_project.service

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.recommendation.DriverCandidateLoader
import com.example.delivery_project.service.component.recommendation.DriverRecommendationContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
class DriverRecommendationSnapshotLoader(
    private val deliveryPlanRepository: DeliveryPlanRepository,
    private val driverCandidateLoader: DriverCandidateLoader,
    private val claimProperties: DeliveryClaimProperties,
) {
    @Transactional(readOnly = true)
    fun load(planId: Long): DriverRecommendationSnapshot {
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
        return DriverRecommendationSnapshot(
            planId = planId,
            departureLocation = plan.departureLocation,
            scheduledDepartureAt = plan.scheduledDepartureAt,
            evaluatedAt = evaluatedAt,
            candidateCount = loaded.candidates.size,
            excludedByClaimLimit = loaded.excludedByClaimLimit,
            context = driverCandidateLoader.toContext(plan, loaded.candidates, evaluatedAt),
        )
    }
}

data class DriverRecommendationSnapshot(
    val planId: Long,
    val departureLocation: String,
    val scheduledDepartureAt: LocalDateTime,
    val evaluatedAt: LocalDateTime,
    val candidateCount: Int,
    val excludedByClaimLimit: Int,
    val context: DriverRecommendationContext,
)
