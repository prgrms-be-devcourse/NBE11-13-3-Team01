package com.example.delivery_project.service

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanPriorityDriverRepository
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.response.ClaimDeliveryPlanResponse
import com.example.delivery_project.dto.response.OpenDeliveryPlanResponse
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class DeliveryPlanClaimService(
    private val deliveryPlanRepository: DeliveryPlanRepository,
    private val priorityDriverRepository: DeliveryPlanPriorityDriverRepository,
    private val userRepository: UserRepository,
    private val claimProperties: DeliveryClaimProperties,
    meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val claimSucceeded = counter(meterRegistry, "success")
    private val claimIdempotent = counter(meterRegistry, "idempotent")
    private val claimConflicted = counter(meterRegistry, "conflict")
    private val claimLimitExceeded = counter(meterRegistry, "limit_exceeded")
    private val claimPriorityBlocked = counter(meterRegistry, "priority_blocked")
    private val releaseSucceeded = Counter.builder(RELEASE_METRIC)
        .description("배송 업무 반납 처리 수")
        .register(meterRegistry)

    fun getOpenPlans(driverId: Long): List<OpenDeliveryPlanResponse> {
        val now = LocalDateTime.now()
        val plans = deliveryPlanRepository.findOpenSummariesForDriver(driverId)
        log.info("[CLAIM] 미배정 업무 목록 조회 완료 driverId: {}, planSize: {}", driverId, plans.size)
        return plans.map { OpenDeliveryPlanResponse.from(it, now) }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun claim(planId: Long, driverId: Long): ClaimDeliveryPlanResponse {
        val driver = lockDriver(driverId)
        val plan = findPlan(planId)
        if (plan.isOwnedBy(driverId)) {
            claimIdempotent.increment()
            log.info("[CLAIM] 이미 본인이 보유한 업무 재요청 planId: {}, driverId: {}", planId, driverId)
            return idempotentResponse(plan, driverId)
        }
        if (!plan.isClaimable) {
            claimConflicted.increment()
            log.info("[CLAIM] 수령 불가 상태 planId: {}, driverId: {}, status: {}", planId, driverId, plan.status)
            throw BusinessException(DeliveryException.DELIVERY_PLAN_ALREADY_CLAIMED)
        }
        if (plan.isPriorityWindowActive(LocalDateTime.now()) && !hasPriority(planId, driverId)) {
            claimPriorityBlocked.increment()
            log.info(
                "[CLAIM] 우선 수령 구간이라 거부 planId: {}, driverId: {}, publicAt: {}",
                planId, driverId, plan.publicAt,
            )
            throw BusinessException(DeliveryException.DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE)
        }
        val activeCount = countActive(requireNotNull(driver.id))
        if (activeCount >= claimProperties.maxActivePlans) {
            claimLimitExceeded.increment()
            log.info(
                "[CLAIM] 보유 한도 초과로 수령 거부 planId: {}, driverId: {}, activeCount: {}, limit: {}",
                planId, driverId, activeCount, claimProperties.maxActivePlans,
            )
            throw BusinessException(DeliveryException.DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED)
        }
        val claimedAt = LocalDateTime.now()
        val updatedRows = deliveryPlanRepository.claimIfOpen(planId, driverId, claimedAt)
        if (updatedRows == 0) {
            val current = deliveryPlanRepository.findWithDriverById(planId)
            if (current != null && current.isOwnedBy(driverId)) {
                claimIdempotent.increment()
                log.info("[CLAIM] 조건부 UPDATE 실패했으나 이미 본인 소유 planId: {}, driverId: {}", planId, driverId)
                return idempotentResponse(current, driverId)
            }
            throw claimFailure(planId, driverId, current)
        }

        claimSucceeded.increment()
        val claimedPlan = findPlan(planId)
        log.info("[CLAIM] 수령 완료 planId: {}, driverId: {}, activeCount: {}", planId, driverId, activeCount + 1)
        return ClaimDeliveryPlanResponse.from(
            claimedPlan,
            alreadyOwned = false,
            activePlanCount = activeCount + 1,
        )
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun release(planId: Long, driverId: Long) {
        lockDriver(driverId)

        val plan = findPlan(planId)
        if (!plan.isOwnedBy(driverId)) {
            throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_FOUND)
        }
        if (!plan.status.isReady()) {
            throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_RELEASABLE)
        }

        val updatedRows = deliveryPlanRepository.releaseIfOwnedAndReady(planId, driverId)
        if (updatedRows == 0) {
            throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_RELEASABLE)
        }

        releaseSucceeded.increment()
        log.info("[CLAIM] 반납 완료 planId: {}, driverId: {}", planId, driverId)
    }

    private fun claimFailure(planId: Long, driverId: Long, current: DeliveryPlan?): BusinessException {
        if (current != null &&
            current.isClaimable &&
            current.isPriorityWindowActive(LocalDateTime.now()) &&
            !hasPriority(planId, driverId)
        ) {
            claimPriorityBlocked.increment()
            log.info("[CLAIM] 우선 수령 구간이라 수령 실패 planId: {}, driverId: {}", planId, driverId)
            return BusinessException(DeliveryException.DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE)
        }
        claimConflicted.increment()
        log.info("[CLAIM] 경합에서 밀려 수령 실패 planId: {}, driverId: {}", planId, driverId)
        return BusinessException(DeliveryException.DELIVERY_PLAN_ALREADY_CLAIMED)
    }

    private fun hasPriority(planId: Long, driverId: Long): Boolean =
        priorityDriverRepository.existsByDeliveryPlanIdAndDriverId(planId, driverId)

    private fun lockDriver(driverId: Long): User =
        userRepository.findUserByIdForUpdate(driverId)
            ?.takeIf { it.role == Role.ROLE_DELIVERY_DRIVER }
            ?: throw BusinessException(DeliveryException.DELIVERY_DRIVER_NOT_FOUND)

    private fun findPlan(planId: Long): DeliveryPlan =
        deliveryPlanRepository.findWithDriverById(planId)
            ?: throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_FOUND)

    private fun idempotentResponse(plan: DeliveryPlan, driverId: Long) =
        ClaimDeliveryPlanResponse.from(plan, alreadyOwned = true, activePlanCount = countActive(driverId))

    private fun countActive(driverId: Long): Long =
        deliveryPlanRepository.countByDriverIdAndStatusIn(driverId, DeliveryPlanStatus.ACTIVE_STATUSES)

    private fun counter(meterRegistry: MeterRegistry, result: String): Counter =
        Counter.builder(CLAIM_METRIC)
            .description("배송 업무 수령 요청 처리 결과")
            .tag("result", result)
            .register(meterRegistry)

    private companion object {
        const val CLAIM_METRIC = "delivery.plan.claim"
        const val RELEASE_METRIC = "delivery.plan.release"
    }
}
