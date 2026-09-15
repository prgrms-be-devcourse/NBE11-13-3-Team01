package com.example.delivery_project.batch

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Batch 6 부터 기본 JobRepository 는 메모리 기반(ResourcelessJobRepository)이다.
 * 실행 이력을 DB에 남기려면 JDBC 저장소를 명시해야 한다.
 *
 * @EnableJdbcJobRepository 는 @Import 가 없는 표시용 애너테이션이라
 * 이를 읽는 BatchRegistrar 를 가져오는 @EnableBatchProcessing 과 함께 있어야 동작한다.
 *
 * JobRepository 를 주입받는 설정과 분리해 둔다. 같은 클래스에 두면 순환이 생긴다.
 */
@Configuration
@Profile("batch")
@EnableBatchProcessing
@EnableJdbcJobRepository
class BatchInfrastructureConfig
