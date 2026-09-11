package com.example.delivery_project.scheduler

import com.example.delivery_project.service.DeliveryRiskRefreshService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WeatherBatchSchedulerTest {
    private val refreshService = mock<DeliveryRiskRefreshService>()
    private val scheduler = WeatherBatchScheduler(refreshService)

    @Test
    fun 애플리케이션_시작과_매시_45분에_활성_배송지를_갱신한다() {
        scheduler.refreshOnStartup()
        scheduler.refreshHourly()
        verify(refreshService, times(2)).refreshActiveStops()
    }

    @Test
    fun 배치_실패가_스케줄러_밖으로_전파되지_않는다() {
        doThrow(IllegalStateException("갱신 실패")).whenever(refreshService).refreshActiveStops()
        scheduler.refreshHourly()
    }
}
