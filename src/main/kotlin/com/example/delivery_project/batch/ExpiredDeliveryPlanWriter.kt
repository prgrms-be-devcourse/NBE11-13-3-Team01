package com.example.delivery_project.batch

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.stereotype.Component

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
