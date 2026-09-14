package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.config.PriorityWindowProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanPriorityDriver
import com.example.delivery_project.domain.repository.DeliveryPlanPriorityDriverRepository
import com.example.delivery_project.domain.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 배송 업무를 등록할 때 추천 상위 기사에게 짧은 독점 수령 구간을 부여한다.
 *
 * 선착순만 두면 알림을 먼저 본 기사가 가져가고, 반대로 관리자가 직접 배정하면 기사의 선택권이 사라진다.
 * 우선권 윈도우는 그 사이를 메운다.
 * 적합도가 높은 기사에게 먼저 기회를 주되 강제 배정은 하지 않고,
 * 아무도 가져가지 않으면 공개 시각 이후 전체가 선착순으로 경쟁한다.
 *
 * 우선권 **판정**은 이 클래스가 아니라 수령 시점의 조건부 UPDATE 가 DB 에서 수행한다.
 * 여기서는 대상 목록과 공개 시각을 정할 뿐이다.
 */
@Component
class PriorityWindowAssigner(
    private val driverCandidateLoader: DriverCandidateLoader,
    private val driverRecommender: DriverRecommender,
    private val priorityDriverRepository: DeliveryPlanPriorityDriverRepository,
    private val userRepository: UserRepository,
    private val priorityWindowProperties: PriorityWindowProperties,
    private val claimProperties: DeliveryClaimProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 저장된 미배정 계획에 우선권 윈도우를 연다.
     * 윈도우가 꺼져 있거나 추천 후보가 없으면 아무것도 하지 않고 즉시 전체 공개 상태로 둔다.
     */
    fun open(plan: DeliveryPlan, now: LocalDateTime): List<DeliveryPlanPriorityDriver> {
        if (!priorityWindowProperties.enabled || priorityWindowProperties.driverCount <= 0) {
            return emptyList()
        }

        val loaded = driverCandidateLoader.load(maxActivePlans = claimProperties.maxActivePlans)
        if (loaded.candidates.isEmpty()) {
            log.info("[PRIORITY] 우선권 후보가 없어 즉시 전체 공개한다 planId: {}", plan.id)
            return emptyList()
        }

        val scoredDrivers = driverRecommender.recommend(
            driverCandidateLoader.toContext(plan, loaded.candidates, now),
            priorityWindowProperties.driverCount,
        )
        if (scoredDrivers.isEmpty()) return emptyList()

        val driversById = userRepository
            .findAllById(scoredDrivers.map { it.candidate.driverId })
            .associateBy { requireNotNull(it.id) }
        val priorityDrivers = scoredDrivers.mapIndexedNotNull { index, scored ->
            driversById[scored.candidate.driverId]?.let { driver ->
                DeliveryPlanPriorityDriver.of(plan, driver, index + 1, scored.score)
            }
        }
        if (priorityDrivers.isEmpty()) return emptyList()

        val publicAt = now.plus(priorityWindowProperties.window())
        plan.openPriorityWindow(publicAt)
        val saved = priorityDriverRepository.saveAll(priorityDrivers)

        log.info(
            "[PRIORITY] 우선 수령 윈도우 개시 planId: {}, publicAt: {}, driverIds: {}",
            plan.id, publicAt, saved.map { it.driver.id },
        )
        return saved
    }
}
