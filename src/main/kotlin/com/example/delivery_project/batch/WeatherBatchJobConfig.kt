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
    fun weatherRefreshJob(weatherRefreshStep: Step): Job =
        JobBuilder("weatherRefreshJob", jobRepository)
            .start(weatherRefreshStep)
            .build()

    @Bean
    fun weatherRefreshStep(): Step =
        StepBuilder("weatherRefreshStep", jobRepository)
            .tasklet ({ _, _ ->
                deliveryRiskRefreshService.refreshActiveStops()
                RepeatStatus.FINISHED
            }, transactionManager)
            .build()
}