package com.example.delivery_project.scheduler

import com.example.delivery_project.service.DeliveryRiskRefreshService
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
@Profile("batch")
class WeatherBatchScheduler(
    private val jobOperator: JobOperator,
    private val weatherRefreshJob: Job,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun refreshOnStartup() = launch("애플리케이션 시작")

    @Scheduled(cron = "0 45 * * * *", zone = "Asia/Seoul")
    fun refreshHourly() = launch("매시 45분 배치")

    private fun launch(trigger:String){
        log.info("[날씨·위험도 갱신 시작] trigger={}", trigger)
        try{
            val parameters = JobParametersBuilder()
                .addString("trigger", trigger)
                .addLocalDateTime("runAt", LocalDateTime.now())
                .toJobParameters()
            jobOperator.start(weatherRefreshJob,parameters)
        } catch (e: Exception) {
            log.error("[날씨·위험도 갱신 실패] trigger={}", trigger, e)
        }
    }
}
