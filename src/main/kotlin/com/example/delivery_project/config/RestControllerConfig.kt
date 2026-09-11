package com.example.delivery_project.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
class RestControllerConfig {
    @Bean
    fun restClient(): RestClient = RestClient.builder().build()
}
