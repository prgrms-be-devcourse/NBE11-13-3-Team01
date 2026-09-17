package com.example.delivery_project.batch

import com.example.delivery_project.service.DeliveryRiskRefreshService
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.transaction.PlatformTransactionManager

@Configuration
@Profile("batch")
class WeatherBatchJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val deliveryRiskRefreshService: DeliveryRiskRefreshService,
) {
    @Bean
    fun weatherRefreshJob(
        weatherRefreshStep: Step,
        batchResultNotificationListener: BatchResultNotificationListener,
    ): Job =
        JobBuilder("weatherRefreshJob", jobRepository)
            .listener(batchResultNotificationListener)
            .start(weatherRefreshStep)
            .build()

    @Bean
    fun weatherRefreshStep(): Step =
        StepBuilder("weatherRefreshStep", jobRepository)
            .tasklet({ contribution, _ ->
                val summary = deliveryRiskRefreshService.refreshActiveStops()

                // Tasklet 은 Chunk 와 달리 처리 건수를 스프링 배치가 세어주지 않는다.
                // 직접 넣지 않으면 실제로 51건을 갱신해도 기록과 알림에 0 으로 남는다.
                contribution.incrementWriteCount(summary.stopCount.toLong())
                contribution.stepExecution.executionContext.putString(
                    STEP_SUMMARY_KEY,
                    "배송지 ${summary.stopCount}건, 좌표 ${summary.coordinateCount}곳, " +
                        "날씨 갱신 실패 ${summary.failedCoordinateCount}곳",
                )

                // 일부 좌표가 실패해도 저장된 날씨로 버틸 수 있어 성공으로 본다.
                // 전부 실패했다면 기상 API 가 죽은 것이므로 Step 을 실패시켜 드러낸다.
                check(!summary.allCoordinatesFailed) {
                    "모든 좌표의 날씨 갱신에 실패했습니다. coordinateCount=${summary.coordinateCount}"
                }
                RepeatStatus.FINISHED
            }, transactionManager)
            .build()

    companion object {
        /** 배치 결과 알림이 이 값을 찾아 사람이 읽을 수 있는 요약으로 쓴다. */
        const val STEP_SUMMARY_KEY = "step.summary"
    }
}