package com.example.delivery_project.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 주기 배치는 batch 프로필에서만 동작한다.
 * 웹 인스턴스를 여러 대로 늘려도 배치가 중복 실행되지 않도록 하기 위함이다.
 */
@Configuration
@Profile("batch")
@EnableScheduling
class SchedulingConfig
