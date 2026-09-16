package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DriverRecommendationProperties
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors

class N8nAiRecommendationGatewayTest {
    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun tearDown() {
        servers.forEach { it.stop(0) }
    }

    @Test
    fun `4xx와 5xx는 HTTP 오류로 분류한다`() {
        val server = server { exchange ->
            exchange.sendResponseHeaders(if (exchange.requestURI.path == "/4xx") 400 else 503, 0)
            exchange.close()
        }
        assertThat(gateway("http://127.0.0.1:${server.address.port}/4xx").recommend(request()))
            .isEqualTo(AiGatewayResult.Failure(AiRecommendationResult.HTTP_ERROR))
        assertThat(gateway("http://127.0.0.1:${server.address.port}/5xx").recommend(request()))
            .isEqualTo(AiGatewayResult.Failure(AiRecommendationResult.HTTP_ERROR))
    }

    @Test
    fun `malformed JSON과 빈 응답은 invalid response로 분류한다`() {
        val server = server { exchange ->
            val body = if (exchange.requestURI.path == "/empty") "" else "not-json"
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        assertThat(gateway("http://127.0.0.1:${server.address.port}/bad").recommend(request()))
            .isEqualTo(AiGatewayResult.Failure(AiRecommendationResult.INVALID_RESPONSE))
        assertThat(gateway("http://127.0.0.1:${server.address.port}/empty").recommend(request()))
            .isEqualTo(AiGatewayResult.Failure(AiRecommendationResult.INVALID_RESPONSE))
    }

    @Test
    fun `느린 응답은 timeout으로 분류한다`() {
        val server = server { exchange ->
            Thread.sleep(200)
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        val result = gateway("http://127.0.0.1:${server.address.port}/slow", readTimeout = Duration.ofMillis(30))
            .recommend(request())

        assertThat(result).isEqualTo(AiGatewayResult.Failure(AiRecommendationResult.TIMEOUT))
    }

    @Test
    fun `잘못된 URL은 설정 오류로 분류한다`() {
        assertThat(gateway("file:///tmp/n8n").recommend(request()))
            .isEqualTo(AiGatewayResult.Failure(AiRecommendationResult.CONFIG_ERROR))
    }

    @Test
    fun `연결할 수 없는 주소는 전송 오류로 분류한다`() {
        val unusedPort = java.net.ServerSocket(0).use { it.localPort }
        val result = gateway("http://127.0.0.1:$unusedPort/recommend").recommend(request())

        assertThat(result).isEqualTo(AiGatewayResult.Failure(AiRecommendationResult.TRANSPORT_ERROR))
    }

    private fun gateway(url: String, readTimeout: Duration = Duration.ofSeconds(1)) =
        N8nAiRecommendationGateway(
            DriverRecommendationProperties().apply {
                ai.enabled = true
                ai.webhookUrl = url
                ai.secret = "test-secret"
                ai.readTimeout = readTimeout
            },
            JsonMapper.builder().build(),
            SimpleMeterRegistry(),
        )

    private fun request() = AiRecommendationRequest(
        requestId = "request-1",
        evaluatedAt = java.time.LocalDateTime.of(2026, 9, 16, 12, 0),
        target = AiRecommendationTarget(java.time.LocalDateTime.of(2026, 9, 16, 13, 0), 1, 1, 0),
        requestedDriverCount = 1,
        candidates = emptyList(),
    )

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.executor = Executors.newCachedThreadPool()
        server.start()
        servers += server
        return server
    }
}
