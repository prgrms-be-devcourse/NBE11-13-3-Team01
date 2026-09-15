package com.example.delivery_project.batch

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.stereotype.Component

/**
 * 배송 계획을 엔티티 단위로 삭제한다.
 *
 * deleteAll 은 내부적으로 건별 remove 를 호출하므로 cascade 가 동작해
 * 배송지, 배송 상품, 위험도, 위험 요인이 함께 지워진다.
 * deleteAllInBatch 는 벌크 쿼리라 cascade 를 타지 않으므로 쓰면 안 된다.
 */
@Component
class ExpiredDeliveryPlanWriter(
    private val deliveryPlanRepository: DeliveryPlanRepository,
) : ItemWriter<DeliveryPlan> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun write(chunk: Chunk<out DeliveryPlan>) {
        if (chunk.isEmpty) return
        deliveryPlanRepository.deleteAll(chunk.items)
        log.info("만료된 배송 계획을 삭제했습니다. count={}, planIds={}", chunk.size(), chunk.items.map { it.id })
    }
}
