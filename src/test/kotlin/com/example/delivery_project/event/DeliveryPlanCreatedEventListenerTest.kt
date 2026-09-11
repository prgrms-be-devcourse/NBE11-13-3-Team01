package com.example.delivery_project.event

import com.example.delivery_project.service.DeliveryRiskRefreshService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class DeliveryPlanCreatedEventListenerTest {
    private val refreshService = mock<DeliveryRiskRefreshService>()
    private val listener = DeliveryPlanCreatedEventListener(refreshService)

    @Test
    fun 계획_생성_커밋_후_해당_계획의_위험도를_갱신한다() {
        listener.refreshRiskAfterPlanCreated(DeliveryPlanCreatedEvent(10))
        verify(refreshService).refreshPlan(10)
    }

    @Test
    fun 생성_후_갱신_실패는_계획_생성에_전파하지_않는다() {
        doThrow(IllegalStateException("갱신 실패")).whenever(refreshService).refreshPlan(10)
        listener.refreshRiskAfterPlanCreated(DeliveryPlanCreatedEvent(10))
    }
}
