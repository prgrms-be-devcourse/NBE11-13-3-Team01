package com.example.delivery_project.batch

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * 배치 결과를 슬랙 채널로 보낸다.
 *
 * 공용 RestClient 빈을 쓰지 않고 전용 클라이언트를 만든다.
 * 공용 빈에는 타임아웃이 없어서 슬랙이 응답하지 않으면 배치 스레드가 그대로 묶인다.
 *
 * 웹훅 주소는 그 자체가 비밀값이다. 로그에 찍지 않는다.
 */
//우체부
@Component
@Profile("batch")
class SlackNotifier(
    @param:Value("\${slack.webhook-url:}") private val webhookUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
            ).apply { setReadTimeout(READ_TIMEOUT) },
        )
        .build()

    //슬랙 웹훅 주소로 메시지 전송
    fun send(message: String) {
        //웹훅 주소 없으면 전송X
        if (webhookUrl.isBlank()) {
            log.info("슬랙 웹훅 주소가 설정되지 않아 알림을 건너뜁니다.")
            return
        }
        try {
            restClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("text" to message))
                .retrieve()
                .toBodilessEntity()
            log.info("슬랙 알림을 전송했습니다.")
        } catch (e: Exception) {
            // 알림이 실패했다고 배치 결과가 바뀌어서는 안 된다. 로그만 남기고 삼킨다.
            log.error("슬랙 알림 전송에 실패했습니다.", e)
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
