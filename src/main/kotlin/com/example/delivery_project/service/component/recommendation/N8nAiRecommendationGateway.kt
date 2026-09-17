package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DriverRecommendationProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException

@Component
@ConditionalOnProperty(
    prefix = "delivery.recommendation.ai",
    name = ["enabled"],
    havingValue = "true",
)
class N8nAiRecommendationGateway(
    recommendationProperties: DriverRecommendationProperties,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : AiRecommendationGateway {
    private val properties = recommendationProperties.ai
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .build()

    override fun recommend(request: AiRecommendationRequest): AiGatewayResult {
        val webhookUri = parseWebhookUri() ?: run {
            log.warn("[AI-RECOMMEND] n8n webhook URL 설정이 없거나 유효하지 않다")
            return failure(AiRecommendationResult.CONFIG_ERROR)
        }
        val httpRequest = try {
            HttpRequest.newBuilder(webhookUri)
                .timeout(properties.readTimeout)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(AUTH_HEADER, properties.secret)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build()
        } catch (e: Exception) {
            log.warn("[AI-RECOMMEND] n8n 요청 생성 실패 cause={}", e.javaClass.simpleName)
            return failure(AiRecommendationResult.INVALID_RESPONSE)
        }

        return try {
            val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                log.warn("[AI-RECOMMEND] n8n HTTP 오류 status={}", response.statusCode())
                failure(AiRecommendationResult.HTTP_ERROR)
            } else {
                try {
                    val body = objectMapper.readValue(response.body(), AiRecommendationResponse::class.java)
                    AiGatewayResult.Success(body)
                } catch (e: Exception) {
                    log.warn("[AI-RECOMMEND] n8n 응답 JSON 형식 오류 cause={}", e.javaClass.simpleName)
                    failure(AiRecommendationResult.INVALID_RESPONSE)
                }
            }
        } catch (e: HttpTimeoutException) {
            log.warn("[AI-RECOMMEND] n8n 응답 시간 초과")
            failure(AiRecommendationResult.TIMEOUT)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("[AI-RECOMMEND] n8n 호출 중 인터럽트")
            failure(AiRecommendationResult.TRANSPORT_ERROR)
        } catch (e: Exception) {
            log.warn("[AI-RECOMMEND] n8n 호출 또는 응답 역직렬화 실패 cause={}", e.javaClass.simpleName)
            failure(AiRecommendationResult.TRANSPORT_ERROR)
        }
    }

    private fun parseWebhookUri(): URI? = try {
        URI.create(properties.webhookUrl.trim()).takeIf { uri ->
            (uri.scheme == "http" || uri.scheme == "https") && uri.host != null
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun failure(result: AiRecommendationResult): AiGatewayResult.Failure {
        meterRegistry.counter(METRIC_NAME, "result", result.metricValue).increment()
        return AiGatewayResult.Failure(result)
    }

    private companion object {
        const val AUTH_HEADER = "X-N8N-Secret"
        const val METRIC_NAME = "delivery_ai_recommendation_total"
    }
}
