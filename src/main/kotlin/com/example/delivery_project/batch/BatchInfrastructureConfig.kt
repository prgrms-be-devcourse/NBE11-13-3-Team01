package com.example.delivery_project.batch

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("batch")
@EnableBatchProcessing
@EnableJdbcJobRepository
class BatchInfrastructureConfig
