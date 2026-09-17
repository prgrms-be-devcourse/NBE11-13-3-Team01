package com.example.delivery_project.service.component.route

import com.example.delivery_project.config.RemoteRouteOptimizerProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

@Component
@Primary
@ConditionalOnExpression("\${route-optimizer.remote.enabled:false} and '\${route-optimizer.remote.transport:http}' == 'udp'")
class UdpRouteOptimizer(
    properties: RemoteRouteOptimizerProperties,
    private val objectMapper: ObjectMapper,
    private val fallback: DijkstraRouteOptimizer,
) : RouteOptimizer {
    private val log = LoggerFactory.getLogger(javaClass)
    private val remoteProperties = properties

    override fun optimize(context: RouteOptimizationContext): OptimizedRoute {
        val matrix = RouteMatrix.from(context)
        if (matrix.candidateCount == 0) return OptimizedRoute(emptyList(), 0L, 1)
        return try {
            val request = objectMapper.writeValueAsBytes(UdpRouteRequest(matrix.nodeCount, matrix.costs.toList()))
            DatagramSocket().use { socket ->
                socket.soTimeout = remoteProperties.readTimeout.toMillis().coerceAtLeast(1L).toInt()
                val address = InetSocketAddress(remoteProperties.udpHost, remoteProperties.udpPort)
                socket.send(DatagramPacket(request, request.size, address))
                val buffer = ByteArray(65507)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val response = objectMapper.readValue(packet.data.copyOf(packet.length), UdpRouteResponse::class.java)
                val optimized = toOptimizedRoute(response, matrix)
                if (optimized == null) fallbackWithLog("응답 계약 위반", context) else {
                    log.info("[ROUTE] C UDP 최적화 성공 source=c-route-optimizer-udp candidateCount={}, totalDurationSeconds={}", context.candidateStopIds.size, optimized.totalDurationSeconds)
                    optimized
                }
            }
        } catch (exception: Exception) {
            fallbackWithLog(exception.javaClass.simpleName, context)
        }
    }

    private fun toOptimizedRoute(response: UdpRouteResponse, matrix: RouteMatrix): OptimizedRoute? {
        if (response.status != 0 || response.routeLength != matrix.candidateCount || response.totalDuration < 0 || response.route.size != matrix.candidateCount) return null
        if (response.route.toSet().size != response.route.size || response.route.any { it !in 1..matrix.candidateCount }) return null
        return OptimizedRoute(matrix.toStopIds(response.route.toIntArray(), matrix.candidateCount), response.totalDuration, response.reachableStates.toInt())
    }

    private fun fallbackWithLog(reason: String, context: RouteOptimizationContext): OptimizedRoute {
        log.warn("[ROUTE] C UDP 최적화 실패로 Kotlin fallback 사용 reason={}, candidateCount={}", reason, context.candidateStopIds.size)
        return fallback.optimize(context)
    }
}

private data class UdpRouteRequest(
    val nodeCount: Int,
    val costs: List<Long>,
)

private data class UdpRouteResponse(
    val status: Int,
    val routeLength: Int,
    val totalDuration: Long,
    val reachableStates: Long,
    val route: List<Int>,
)
