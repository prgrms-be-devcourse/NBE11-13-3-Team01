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

//완료된 배송 계획 정리 배치. 트래픽이 적은 새벽에 실행한다.
@Component
@Profile("batch")
class DeliveryPlanCleanupScheduler(
    private val jobOperator: JobOperator,
    private val deliveryPlanCleanupJob: Job,
    @param:Value("\${delivery.cleanup.retention-days:30}") private val retentionDays: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 배치 테스트 : 1분 주기로 변경. 주기를 바꿀 때는 코드가 아니라 실행 인자로 덮어쓴다.
    //   --delivery.cleanup.cron="0 * * * * *"
    @Scheduled(cron = "\${delivery.cleanup.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    fun cleanupDaily() = launch("매일 04시 정리 배치")

    fun launch(trigger: String) {
        // 기준 시각은 배치 시작 시각에서 보관 기간을 뺀 값이다.
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
