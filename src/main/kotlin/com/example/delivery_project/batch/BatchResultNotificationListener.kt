package com.example.delivery_project.batch

import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.listener.JobExecutionListener
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * Job 이 끝나면 결과를 요약해 슬랙으로 보낸다.
 *
 * afterJob 은 성공했을 때도 실패했을 때도 호출된다.
 * 매일 도는 배치가 조용히 실패하는 상황을 막는 것이 목적이므로 양쪽 다 알린다.
 */

@Component
@Profile("batch")
class BatchResultNotificationListener(
    private val slackNotifier: SlackNotifier,
) : JobExecutionListener {

    //작업 완료 후 슬랙 메시지 발송
    override fun afterJob(jobExecution: JobExecution) {
        slackNotifier.send(buildMessage(jobExecution))
    }

    //메시지 생성
    private fun buildMessage(jobExecution: JobExecution): String {
        val rawName = jobExecution.jobInstance.jobName
        val jobName = DISPLAY_NAMES[rawName] ?: rawName
        val succeeded = jobExecution.status == BatchStatus.COMPLETED

        val lines = mutableListOf(
            if (succeeded) ":white_check_mark: *$jobName 배치 성공*" else ":rotating_light: *$jobName 배치 실패*",
            "• 상태: ${jobExecution.status}",
        )

        jobExecution.startTime?.let { lines += "• 시작: ${it.format(TIME_FORMATTER)}" }
        elapsedText(jobExecution)?.let { lines += "• 소요: $it" }

        // Tasklet Step 은 읽음·처리 건수를 스프링 배치가 세지 않아 그대로 두면 0 으로 나온다.
        // Step 이 직접 남긴 요약이 있으면 그걸 우선한다.
        jobExecution.stepExecutions.forEach { step ->
            val summary = step.executionContext.getString(WeatherBatchJobConfig.STEP_SUMMARY_KEY, "")
            lines += if (summary.isNotBlank()) {
                "• ${step.stepName}: $summary"
            } else {
                "• ${step.stepName}: 읽음 ${step.readCount}건, 처리 ${step.writeCount}건, " +
                    "건너뜀 ${step.skipCount}건, 롤백 ${step.rollbackCount}회"
            }
        }

        // 실패 원인은 앞의 세 건만 싣는다. 전부 붙이면 메시지가 읽기 어려워진다.
        val failures = jobExecution.allFailureExceptions
        if (failures.isNotEmpty()) {
            lines += "• 실패 원인: " +
                failures.take(MAX_FAILURE_LINES).joinToString(" / ") {
                    "${it.javaClass.simpleName}: ${it.message.orEmpty().take(MAX_MESSAGE_LENGTH)}"
                }
        }

        return lines.joinToString("\n")
    }

    private fun elapsedText(jobExecution: JobExecution): String? {
        val start = jobExecution.startTime ?: return null
        val end = jobExecution.endTime ?: return null
        val millis = Duration.between(start, end).toMillis()
        return "%.1f초".format(millis / 1000.0)
    }

    private companion object {
        val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        const val MAX_FAILURE_LINES = 3
        const val MAX_MESSAGE_LENGTH = 200

        // Job 이름을 그대로 보내면 채널에서 알아보기 어렵다.
        val DISPLAY_NAMES = mapOf(
            "deliveryPlanCleanupJob" to "완료 배송 정리",
            "weatherRefreshJob" to "날씨·위험도 갱신",
        )
    }
}
