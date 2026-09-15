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

/**
 * 보관 기간이 지난 완료 배송 계획을 정리하는 Job.
 *
 * 청크 방식을 쓰는 이유는 두 가지다.
 * - 한 트랜잭션에 전부 넣으면 락이 오래 잡혀 API 응답에 영향을 준다.
 * - 중간에 실패해도 앞선 청크는 커밋되어 남고, 다음 실행이 이어서 간다.
 */
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
            // Batch 6에서 chunk(size, txManager) 와 SimpleStepBuilder 는 지원 중단됐다.
            // chunk(size) 가 돌려주는 ChunkOrientedStepBuilder 를 쓰고
            // 트랜잭션 매니저는 별도 메서드로 넘긴다.
            .chunk<DeliveryPlan, DeliveryPlan>(chunkSize)
            .transactionManager(transactionManager)
            .reader(expiredDeliveryPlanReader)
            .writer(expiredDeliveryPlanWriter)
            .build()
}
