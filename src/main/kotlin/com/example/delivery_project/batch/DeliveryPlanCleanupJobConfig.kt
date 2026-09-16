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
import org.springframework.dao.DataAccessException
import org.springframework.dao.TransientDataAccessException
import org.springframework.transaction.PlatformTransactionManager


//완료된 배송 계획 삭제 job
@Configuration
@Profile("batch")
class DeliveryPlanCleanupJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    @param:Value("\${delivery.cleanup.chunk-size:100}") private val chunkSize: Int,
    @param:Value("\${delivery.cleanup.retry-limit:3}") private val retryLimit: Long,
    // 한도를 넘으면 Step 을 실패시킨다. 전부 건너뛰고도 성공으로 끝나는 상황을 막는다.
    @param:Value("\${delivery.cleanup.skip-limit:10}") private val skipLimit: Long,
) {

    @Bean
    fun deliveryPlanCleanupJob(
        deliveryPlanCleanupStep: Step,
        batchResultNotificationListener: BatchResultNotificationListener,
    ): Job =
        JobBuilder("deliveryPlanCleanupJob", jobRepository)
            // 성공이든 실패든 끝나면 슬랙으로 결과를 알린다.
            .listener(batchResultNotificationListener)
            .start(deliveryPlanCleanupStep)
            .build()

    @Bean
    fun deliveryPlanCleanupStep(
        expiredDeliveryPlanReader: ExpiredDeliveryPlanReader,
        expiredDeliveryPlanWriter: ExpiredDeliveryPlanWriter,
        deletionSkipListener: DeletionSkipListener,
    ): Step =
        StepBuilder("deliveryPlanCleanupStep", jobRepository)
            // Batch 6에서 chunk(size, txManager) 와 SimpleStepBuilder 는 지원 중단됐다.
            // chunk(size) 가 돌려주는 ChunkOrientedStepBuilder 를 쓰고
            // 트랜잭션 매니저는 별도 메서드로 넘긴다.
            .chunk<DeliveryPlan, DeliveryPlan>(chunkSize)
            .transactionManager(transactionManager)
            .reader(expiredDeliveryPlanReader)
            .writer(expiredDeliveryPlanWriter)
            .faultTolerant()
            //락/동시성 관련 예외가 발생하면 retryLimit 만큼 재시도해라
            .retry(TransientDataAccessException::class.java)
            .retryLimit(retryLimit)
            // 재시도로도 안 되거나, 제약 위반처럼 다시 해도 소용없는 오류는 그 건만 건너뛴다.
            // DataAccessException 이 위 둘의 공통 상위 타입이라 재시도를 소진한 뒤에도 여기로 걸린다.
            .skip(DataAccessException::class.java)
            .skipLimit(skipLimit)
            .skipListener(deletionSkipListener)
            .build()


}
