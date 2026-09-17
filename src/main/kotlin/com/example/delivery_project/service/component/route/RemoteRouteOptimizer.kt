package com.example.delivery_project.service.component.route

import com.example.delivery_project.config.RemoteRouteOptimizerProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Component
@Primary
@ConditionalOnExpression("\${route-optimizer.remote.enabled:false} and '\${route-optimizer.remote.transport:http}' != 'udp'")
class RemoteRouteOptimizer(
    properties: RemoteRouteOptimizerProperties,
    private val objectMapper: ObjectMapper,
    private val fallback: DijkstraRouteOptimizer,
) : RouteOptimizer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val remoteProperties = properties
    private val client = HttpClient.newBuilder()
        .connectTimeout(remoteProperties.connectTimeout)
        .build()

    override fun optimize(context: RouteOptimizationContext): OptimizedRoute {
        val matrix = RouteMatrix.from(context)
        if (matrix.candidateCount == 0) return OptimizedRoute(emptyList(), 0L, 1)

        return try {
            val uri = URI.create(remoteProperties.baseUrl.trim().trimEnd('/') + "/optimize")
            val requestBody = objectMapper.writeValueAsString(
                RemoteRouteRequest(matrix.nodeCount, matrix.costs.toList()),
            )
            val request = HttpRequest.newBuilder(uri)
                .timeout(remoteProperties.readTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                fallbackWithLog("HTTP ${response.statusCode()}", context)
            } else {
                val remote = objectMapper.readValue(response.body(), RemoteRouteResponse::class.java)
                val optimized = toOptimizedRoute(remote, matrix)
                if (optimized == null) {
                    fallbackWithLog("응답 계약 위반", context)
                } else {
                    log.info(
                        "[ROUTE] C 서버 최적화 성공 source=c-route-optimizer candidateCount={}, totalDurationSeconds={}",
                        context.candidateStopIds.size,
                        optimized.totalDurationSeconds,
                    )
                    optimized
                }
            }
        } catch (exception: Exception) {
            fallbackWithLog(exception.javaClass.simpleName, context)
        }
    }

    private fun toOptimizedRoute(response: RemoteRouteResponse, matrix: RouteMatrix): OptimizedRoute? {
        if (response.status != STATUS_OK || response.routeLength != matrix.candidateCount) return null
        if (response.totalDuration < 0 || response.reachableStates !in 0..Int.MAX_VALUE.toLong()) return null
        if (response.route.size != matrix.candidateCount) return null
        if (response.route.toSet().size != response.route.size || response.route.any { it !in 1..matrix.candidateCount }) {
            return null
        }
        return OptimizedRoute(
            matrix.toStopIds(response.route.toIntArray(), matrix.candidateCount),
            response.totalDuration,
            response.reachableStates.toInt(),
        )
    }

    private fun fallbackWithLog(reason: String, context: RouteOptimizationContext): OptimizedRoute {
        log.warn("[ROUTE] C 최적화 서버 실패로 Kotlin fallback 사용 reason={}, candidateCount={}", reason, context.candidateStopIds.size)
        return fallback.optimize(context)
    }

    private companion object {
        const val STATUS_OK = 0
    }
}

private data class RemoteRouteRequest(
    val nodeCount: Int,
    val costs: List<Long>,
)

private data class RemoteRouteResponse(
    val status: Int,
    val routeLength: Int,
    val totalDuration: Long,
    val reachableStates: Long,
    val route: List<Int>,
)
