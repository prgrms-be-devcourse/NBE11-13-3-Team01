package com.example.delivery_project.batch

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.transaction.PlatformTransactionManager

@Configuration
@Profile("batch")
class DeliveryPlanCleanupJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    @param:Value("\${delivery.cleanup.chunk-size:100}") private val chunkSize: Int,
) {

    @Bean
    fun deliveryPlanCleanupJob(deliveryPlanCleanupStep: Step): Job =
        JobBuilder("deliveryPlanCleanupJob", jobRepository)
            .start(deliveryPlanCleanupStep)
            .build()

    @Bean
    fun deliveryPlanCleanupStep(
        expiredDeliveryPlanReader: ExpiredDeliveryPlanReader,
        expiredDeliveryPlanWriter: ExpiredDeliveryPlanWriter,
    ): Step =
        StepBuilder("deliveryPlanCleanupStep", jobRepository)
            .chunk<DeliveryPlan, DeliveryPlan>(chunkSize)
            .transactionManager(transactionManager)
            .reader(expiredDeliveryPlanReader)
            .writer(expiredDeliveryPlanWriter)
            .build()
}
