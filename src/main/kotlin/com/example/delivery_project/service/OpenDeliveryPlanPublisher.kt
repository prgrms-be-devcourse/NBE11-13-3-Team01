package com.example.delivery_project.service

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.config.PriorityWindowProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanPriorityDriver
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanPriorityDriverRepository
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.Role
import com.example.delivery_project.event.DeliveryPlanCreatedEvent
import com.example.delivery_project.service.component.recommendation.PriorityWindowSelection
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/** 계획과 검증된 AI 우선권 목록을 한 번의 짧은 트랜잭션으로 공개한다. */
@Service
class OpenDeliveryPlanPublisher(
    private val deliveryPlanRepository: DeliveryPlanRepository,
    private val priorityDriverRepository: DeliveryPlanPriorityDriverRepository,
    private val userRepository: UserRepository,
    private val priorityWindowProperties: PriorityWindowProperties,
    private val claimProperties: DeliveryClaimProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun publish(plan: DeliveryPlan, selection: PriorityWindowSelection): Long {
        // n8n 호출 뒤 후보 상태가 달라졌을 수 있다. 여러 기사를 잠글 때는 ID 오름차순으로
        // users 행을 먼저 잠가 기존 claim 경로의 users -> delivery_plan 순서를 지킨다.
        val validatedDrivers = lockAndValidateSelectedDrivers(selection)
        val savedPlan = deliveryPlanRepository.save(plan)
        val planId = requireNotNull(savedPlan.id)
        val opened = openWindowIfSelectionStillValid(savedPlan, selection, validatedDrivers)

        eventPublisher.publishEvent(DeliveryPlanCreatedEvent(planId))
        meterRegistry.counter(
            METRIC_NAME,
            "result", if (opened) "opened" else "fail_open",
            "reason", if (!opened && selection.drivers.isNotEmpty()) "candidate_changed" else selection.result.metricValue,
        ).increment()
        log.info(
            "[PLAN] 미배정 업무 등록 완료 planId: {}, publicAt: {}, priorityResult: {}",
            planId, savedPlan.publicAt, selection.result.metricValue,
        )
        return planId
    }

    private fun openWindowIfSelectionStillValid(
        plan: DeliveryPlan,
        selection: PriorityWindowSelection,
        driversById: Map<Long, User>?,
    ): Boolean {
        if (selection.drivers.isEmpty() || driversById == null) return false

        val priorities = selection.drivers.mapIndexed { index, scored ->
            DeliveryPlanPriorityDriver.of(
                deliveryPlan = plan,
                driver = requireNotNull(driversById[scored.candidate.driverId]),
                priorityRank = index + 1,
                score = scored.score,
            )
        }
        plan.openPriorityWindow(LocalDateTime.now().plus(priorityWindowProperties.window()))
        priorityDriverRepository.saveAll(priorities)
        return true
    }

    private fun lockAndValidateSelectedDrivers(selection: PriorityWindowSelection): Map<Long, User>? {
        if (selection.drivers.isEmpty()) return null

        val selectedIds = selection.drivers.map { it.candidate.driverId }
        if (selectedIds.distinct().size != selectedIds.size) return null

        val lockedDrivers = selectedIds.sorted().mapNotNull { driverId ->
            userRepository.findUserByIdForUpdate(driverId)
        }
        val driversById = lockedDrivers.associateBy { requireNotNull(it.id) }
        val allStillEligible = driversById.keys == selectedIds.toSet() && selectedIds.all { driverId ->
            driversById[driverId]?.role == Role.ROLE_DELIVERY_DRIVER &&
                deliveryPlanRepository.countByDriverIdAndStatusIn(
                    driverId,
                    DeliveryPlanStatus.ACTIVE_STATUSES,
                ) < claimProperties.maxActivePlans
        }
        if (!allStillEligible) {
            log.warn("[PRIORITY] 추천 기사 자격이 변경되어 즉시 전체 공개한다")
            return null
        }
        return driversById
    }

    private companion object {
        const val METRIC_NAME = "delivery_priority_window_total"
    }
}
