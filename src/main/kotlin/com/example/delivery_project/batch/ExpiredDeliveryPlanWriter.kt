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
 *
 * 삭제 후 곧바로 flush 한다. Hibernate 는 DELETE 를 커밋 시점까지 미루는데,
 * 커밋은 Step 이 skip/retry 를 적용하는 구간 밖이다. 거기서 제약 위반이 터지면
 * 한 건도 건너뛰지 못한 채 Step 이 죽고, 배치 메타데이터까지 함께 롤백되어
 * 실행 상태가 UNKNOWN 으로 남는다. flush 로 예외를 이 안으로 끌어와야
 * 문제가 된 건만 건너뛰고 나머지를 계속 처리할 수 있다.
 */
@Component
class ExpiredDeliveryPlanWriter(
    private val deliveryPlanRepository: DeliveryPlanRepository,
) : ItemWriter<DeliveryPlan> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun write(chunk: Chunk<out DeliveryPlan>) {
        if (chunk.isEmpty) return
        deliveryPlanRepository.deleteAll(chunk.items)
        deliveryPlanRepository.flush()
        log.info("만료된 배송 계획을 삭제했습니다. count={}, planIds={}", chunk.size(), chunk.items.map { it.id })
    }
}
