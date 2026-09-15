package com.example.delivery_project.batch

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.enums.DeliveryPlanStatus
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 보관 기간이 지난 완료 배송 계획을 한 페이지씩 읽는다.
 *
 * OFFSET 기반 페이징을 쓰지 않는다. 읽으면서 지우기 때문에 다음 페이지의 기준점이
 * 매번 앞으로 당겨져 행을 건너뛴다. 마지막으로 읽은 ID 를 커서로 삼으면
 * 삭제 여부와 무관하게 빠짐없이 읽힌다.
 *
 * 그 ID 를 ExecutionContext 에 저장하므로, 중간에 실패해도 다음 실행이 이어서 간다.
 */
@Component
@StepScope
class ExpiredDeliveryPlanReader(
    private val deliveryPlanRepository: DeliveryPlanRepository,
    @param:Value("\${delivery.cleanup.page-size:100}") private val pageSize: Int,
    @param:Value("#{jobParameters['cutoff']}") private val cutoff: LocalDateTime,
) : ItemStreamReader<DeliveryPlan> {

    private val buffer = ArrayDeque<DeliveryPlan>()
    private var lastId: Long = 0

    override fun open(executionContext: ExecutionContext) {
        lastId = executionContext.getLong(LAST_ID_KEY, 0L)
    }

    override fun update(executionContext: ExecutionContext) {
        executionContext.putLong(LAST_ID_KEY, lastId)
    }

    override fun read(): DeliveryPlan? {
        if (buffer.isEmpty()) {
            buffer.addAll(
                deliveryPlanRepository.findExpiredPlans(
                    status = DeliveryPlanStatus.COMPLETED,
                    cutoff = cutoff,
                    lastId = lastId,
                    pageable = PageRequest.of(0, pageSize),
                ),
            )
        }
        val plan = buffer.removeFirstOrNull() ?: return null
        lastId = requireNotNull(plan.id) { "영속화되지 않은 배송 계획은 삭제 대상이 될 수 없다." }
        return plan
    }

    private companion object {
        const val LAST_ID_KEY = "expiredDeliveryPlanReader.lastId"
    }
}
