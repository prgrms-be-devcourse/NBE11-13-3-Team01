package com.example.delivery_project.config

import com.example.delivery_project.scheduler.WeatherBatchScheduler
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.launch.JobOperator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Configuration

/**
 * 주기 배치는 batch 프로필에서만 떠야 한다.
 * 웹 인스턴스를 여러 대로 늘렸을 때 배치가 중복 실행되는 것을 막는 불변조건이다.
 */
class BatchProfileTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(SchedulerScanConfiguration::class.java)

    @Test
    fun `batch 프로필에서는 스케줄러가 등록된다`() {
        contextRunner.withPropertyValues("spring.profiles.active=batch").run { context ->
            assertThat(context).hasSingleBean(WeatherBatchScheduler::class.java)
            assertThat(context).hasSingleBean(SchedulingConfig::class.java)
        }
    }

    @Test
    fun `web 프로필에서는 스케줄러가 등록되지 않는다`() {
        contextRunner.withPropertyValues("spring.profiles.active=web").run { context ->
            assertThat(context).doesNotHaveBean(WeatherBatchScheduler::class.java)
            assertThat(context).doesNotHaveBean(SchedulingConfig::class.java)
        }
    }

    @Test
    fun `프로필을 지정하지 않으면 스케줄러가 등록되지 않는다`() {
        contextRunner.run { context ->
            assertThat(context).doesNotHaveBean(WeatherBatchScheduler::class.java)
        }
    }

    @Configuration
    @ComponentScan(basePackages = ["com.example.delivery_project.scheduler"])
    @Import(SchedulingConfig::class)
    class SchedulerScanConfiguration {
        @Bean
        fun jobOperator(): JobOperator = mock()

        @Bean
        fun weatherRefreshJob(): Job = mock()
    }
}
