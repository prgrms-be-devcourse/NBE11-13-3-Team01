package com.example.delivery_project.batch

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import org.slf4j.LoggerFactory
import org.springframework.batch.core.listener.SkipListener
import org.springframework.stereotype.Component


//skip 항목에 대해서 로깅
@Component
class DeletionSkipListener : SkipListener<DeliveryPlan, DeliveryPlan> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onSkipInWrite(item: DeliveryPlan, t: Throwable) {
        log.error("배송 계획 삭제를 건너뜁니다. planId={}, 사유={}", item.id, t.javaClass.simpleName, t)
    }

    override fun onSkipInRead(t: Throwable) {
        log.error("삭제 대상 조회 중 건너뜁니다.", t)
    }
}
