package com.example.delivery_project.scheduler

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.launch.JobOperator

class WeatherBatchSchedulerTest {
    private val jobOperator = mock<JobOperator>()
    private val weatherRefreshJob = mock<Job>()
    private val scheduler = WeatherBatchScheduler(jobOperator, weatherRefreshJob)

    init {
        // Batch 6은 start()가 non-null을 반환한다고 선언하므로 목도 값을 돌려줘야 한다.
        whenever(jobOperator.start(any<Job>(), any<JobParameters>()))
            .thenReturn(mock<JobExecution>())
    }

    @Test
    fun 애플리케이션_시작과_매시_45분에_배치_Job을_실행한다() {
        scheduler.refreshOnStartup()
        scheduler.refreshHourly()
        verify(jobOperator, times(2)).start(eq(weatherRefreshJob), any())
    }

    @Test
    fun 매_실행마다_다른_파라미터로_Job을_띄운다() {
        scheduler.refreshOnStartup()
        scheduler.refreshHourly()

        val captured = argumentCaptor<JobParameters>()
        verify(jobOperator, times(2)).start(eq(weatherRefreshJob), captured.capture())

        // JobParameter.equals 는 이름만 비교하므로 값을 직접 꺼내 확인한다.
        // runAt 이 같으면 Batch 가 같은 JobInstance 로 보고 중복 실행을 거부한다.
        val runAts = captured.allValues.map { it.getLocalDateTime("runAt") }
        assertThat(runAts).doesNotHaveDuplicates()
        assertThat(captured.allValues.map { it.getString("trigger") })
            .containsExactly("애플리케이션 시작", "매시 45분 배치")
    }

    @Test
    fun Job_실행_실패가_스케줄러_밖으로_전파되지_않는다() {
        doThrow(IllegalStateException("실행 실패"))
            .whenever(jobOperator).start(eq(weatherRefreshJob), any())
        scheduler.refreshHourly()
    }
}
