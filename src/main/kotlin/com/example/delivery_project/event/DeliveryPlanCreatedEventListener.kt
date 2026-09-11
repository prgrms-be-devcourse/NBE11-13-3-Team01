package com.example.delivery_project.event

import com.example.delivery_project.service.DeliveryRiskRefreshService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class DeliveryPlanCreatedEventListener(private val deliveryRiskRefreshService: DeliveryRiskRefreshService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun refreshRiskAfterPlanCreated(event: DeliveryPlanCreatedEvent) {
        try {
            deliveryRiskRefreshService.refreshPlan(event.planId)
        } catch (e: Exception) {
            log.error("신규 배송 계획의 날씨·위험도 갱신 실패. planId={}", event.planId, e)
        }
    }
}
