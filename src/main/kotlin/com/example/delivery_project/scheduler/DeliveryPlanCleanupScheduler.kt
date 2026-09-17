package com.example.delivery_project.scheduler

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
@Component
@Profile("batch")
class DeliveryPlanCleanupScheduler(
    private val jobOperator: JobOperator,
    private val deliveryPlanCleanupJob: Job,
    @param:Value("\${delivery.cleanup.retention-days:30}") private val retentionDays: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    @Scheduled(cron = "\${delivery.cleanup.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    fun cleanupDaily() = launch("매일 04시 정리 배치")

    fun launch(trigger: String) {
        val cutoff = LocalDateTime.now().minusDays(retentionDays)
        log.info("[완료 배송 정리 시작] trigger={}, cutoff={}", trigger, cutoff)
        try {
            val parameters = JobParametersBuilder()
                .addString("trigger", trigger)
                .addLocalDateTime("cutoff", cutoff)
                .addLocalDateTime("runAt", LocalDateTime.now())
                .toJobParameters()
            jobOperator.start(deliveryPlanCleanupJob, parameters)
        } catch (e: Exception) {
            log.error("[완료 배송 정리 실패] trigger={}", trigger, e)
        }
    }
}
