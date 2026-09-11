package com.example.delivery_project.scheduler

import com.example.delivery_project.service.DeliveryRiskRefreshService
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class WeatherBatchScheduler(private val deliveryRiskRefreshService: DeliveryRiskRefreshService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun refreshOnStartup() = refresh("애플리케이션 시작")

    @Scheduled(cron = "0 45 * * * *", zone = "Asia/Seoul")
    fun refreshHourly() = refresh("매시 45분 배치")

    private fun refresh(trigger: String) {
        log.info("[날씨·위험도 갱신 시작] trigger={}", trigger)
        try {
            deliveryRiskRefreshService.refreshActiveStops()
        } catch (e: Exception) {
            log.error("[날씨·위험도 갱신 실패] trigger={}", trigger, e)
        }
    }
}
