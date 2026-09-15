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

/**
 * 관리자가 등록한 미배정 배송 업무를 배송 기사가 선착순으로 가져가는 흐름을 담당한다.
 *
 * ## 동시성 제어
 *
 * 1. **기사 행 비관적 락** ([UserRepository.findUserByIdForUpdate])
 *    같은 기사의 claim 요청을 직렬화해 "동시 보유 한도"를 원자적으로 검사한다.
 *    반드시 트랜잭션의 **첫 DB 접근**이어야 한다. 아래 격리 수준 항목 참고.
 *
 * 2. **계획 행 원자적 조건부 UPDATE** ([DeliveryPlanRepository.claimIfOpen])
 *    `status = 'OPEN' AND driver_id IS NULL` 조건을 UPDATE 에 실어 보내
 *    "하나의 업무는 한 기사만 가져간다"를 보장한다. 조회-검증-저장이 분리되지 않아
 *    read-modify-write 윈도우 자체가 없다.
 *
 * ## 격리 수준을 READ_COMMITTED 로 낮춘 이유
 *
 * MySQL 기본값인 REPEATABLE READ 에서는 트랜잭션의 첫 consistent read(일반 SELECT)가
 * 스냅샷을 만들고 이후의 일반 SELECT 가 모두 그 스냅샷을 재사용한다.
 * 그래서 기사 행 락을 얻으려고 기다렸다가 획득하더라도, 그 뒤의 보유 수 COUNT 는
 * 락을 기다리는 동안 커밋된 다른 트랜잭션의 결과를 보지 못하고 낡은 값을 돌려준다.
 * 활성 2건 / 한도 3건 상태에서 서로 다른 두 업무를 동시에 claim 하면 양쪽 모두 "2건"을 읽고
 * 최종 4건이 되는 한도 위반이 발생한다.
 *
 * 이를 막기 위해 이 트랜잭션만 READ_COMMITTED 로 실행해 매 SELECT 가 최신 커밋 상태를 읽게 한다.
 * 부수적으로 갭 락이 사라져 조건부 UPDATE 의 데드락 가능성도 줄어든다.
 *
 * ## 락 순서
 *
 * 데드락 방지를 위해 항상 `users` -> `delivery_plan` 순서로만 잠근다.
 */
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

    /**
     * 아직 아무도 가져가지 않은 배송 업무 목록.
     *
     * 우선 수령 윈도우가 열린 업무도 숨기지 않고 내려보낸다.
     * 목록에서 사라졌다가 공개 시각에 갑자기 나타나면 기사가 기회를 놓치기 때문이다.
     * 대신 각 업무에 본인의 우선권 순위와 공개 시각을 붙여 지금 가져갈 수 있는지 구분하게 한다.
     */
    fun getOpenPlans(driverId: Long): List<OpenDeliveryPlanResponse> {
        val now = LocalDateTime.now()
        val plans = deliveryPlanRepository.findOpenSummariesForDriver(driverId)
        log.info("[CLAIM] 미배정 업무 목록 조회 완료 driverId: {}, planSize: {}", driverId, plans.size)
        return plans.map { OpenDeliveryPlanResponse.from(it, now) }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun claim(planId: Long, driverId: Long): ClaimDeliveryPlanResponse {
        // 1) 기사 행 잠금이 트랜잭션의 첫 DB 접근이어야 한다.
        //    이후의 모든 조회가 "락 획득 이후"의 최신 커밋 상태를 읽도록 보장하기 위해서다.
        val driver = lockDriver(driverId)

        // 2) 락 이후에 계획을 읽으므로, 같은 기사의 직전 동시 요청이 이미 수령했다면 여기서 관측된다.
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

        // 3) 우선 수령 윈도우가 열려 있으면 추천 상위 기사만 가져갈 수 있다.
        //    최종 판정은 아래 조건부 UPDATE 가 하고, 여기서는 정확한 실패 사유를 주기 위해 먼저 확인한다.
        if (plan.isPriorityWindowActive(LocalDateTime.now()) && !hasPriority(planId, driverId)) {
            claimPriorityBlocked.increment()
            log.info(
                "[CLAIM] 우선 수령 구간이라 거부 planId: {}, driverId: {}, publicAt: {}",
                planId, driverId, plan.publicAt,
            )
            throw BusinessException(DeliveryException.DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE)
        }

        // 4) 보유 수 검사 역시 락 이후의 최신 상태를 읽는다.
        val activeCount = countActive(requireNotNull(driver.id))
        if (activeCount >= claimProperties.maxActivePlans) {
            claimLimitExceeded.increment()
            log.info(
                "[CLAIM] 보유 한도 초과로 수령 거부 planId: {}, driverId: {}, activeCount: {}, limit: {}",
                planId, driverId, activeCount, claimProperties.maxActivePlans,
            )
            throw BusinessException(DeliveryException.DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED)
        }

        // 공개 시각 비교에 쓰이므로 UPDATE 직전에 시각을 구한다.
        // 락 대기 중에 공개 시각이 지났다면 그 사실이 반영되어야 한다.
        val claimedAt = LocalDateTime.now()
        val updatedRows = deliveryPlanRepository.claimIfOpen(planId, driverId, claimedAt)
        if (updatedRows == 0) {
            val current = deliveryPlanRepository.findWithDriverById(planId)
            // 재시도·중복 제출로 이미 본인이 소유하게 된 경우는 실패가 아니라 멱등 성공이다.
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

    /**
     * 조건부 UPDATE 가 0행을 돌려준 이유를 최신 상태로 다시 판정한다.
     *
     * 같은 기사의 동시 요청은 기사 행 락에서 직렬화되므로 보통은 다른 기사에게 밀린 경우지만,
     * 우선권 윈도우에 걸린 경우도 구분해야 클라이언트가 "언제 다시 시도하면 되는지"를 알 수 있다.
     */
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
