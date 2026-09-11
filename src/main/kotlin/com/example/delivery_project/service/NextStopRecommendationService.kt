package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.RiskAssessmentRepository
import com.example.delivery_project.dto.response.NextStopRecommendationResponse
import com.example.delivery_project.enums.RiskLevel
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.DrivingDirectionsClient
import com.example.delivery_project.service.component.route.RouteLeg
import com.example.delivery_project.service.component.route.RouteOptimizationContext
import com.example.delivery_project.service.component.route.RouteOptimizer
import com.example.delivery_project.service.component.route.TravelCostMatrix
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Service
@Transactional(readOnly = true)
class NextStopRecommendationService(
    private val deliveryPlanRepository: DeliveryPlanRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val routeOptimizer: RouteOptimizer,
    private val drivingDirectionsClient: DrivingDirectionsClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun recommend(planId: Long, driverId: Long): NextStopRecommendationResponse {
        val plan = deliveryPlanRepository.findWithStopsAndRiskByIdAndDriverId(planId, driverId)
            ?: throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_FOUND)
        if (!plan.status.isDelivering()) {
            throw BusinessException(DeliveryException.DELIVERY_RECOMMENDATION_NOT_AVAILABLE)
        }
        val currentPoint = resolveCurrentPoint(plan)
        val candidates = plan.deliveryStops.filter { !it.status.isCompleted() }.take(CANDIDATE_LIMIT)
        if (candidates.isEmpty()) {
            return NextStopRecommendationResponse.unavailable(currentPoint.responseStopId)
        }
        val candidateIds = candidates.map { requireNotNull(it.id) }
        riskAssessmentRepository.findAllWithFactorsByDeliveryStopIdIn(candidateIds)
        val safestPriority = candidates.minOfWith(compareBy<RiskPriority> { it.levelOrder }.thenBy { it.score }, ::riskPriority)
        val safestCandidates = candidates.filter { riskPriority(it) == safestPriority }
        val travelCostMatrix = createTravelCostMatrix(currentPoint, safestCandidates)
        val optimizedRoute = routeOptimizer.optimize(
            RouteOptimizationContext(
                currentPoint.nodeId,
                safestCandidates.map { requireNotNull(it.id) },
                travelCostMatrix,
            ),
        )
        val recommendedStopId = optimizedRoute.stopIds.first()
        val recommendedStop = safestCandidates.first { it.id == recommendedStopId }
        val estimatedTravelSeconds = requireNotNull(travelCostMatrix.findDuration(currentPoint.nodeId, recommendedStopId))
        val kakaoTravelSeconds = drivingDirectionsClient.findTravelDurationSeconds(
            currentPoint.latitude, currentPoint.longitude, recommendedStop.latitude, recommendedStop.longitude,
        )
        log.info(
            "[ROUTE] 다음 배송지 추천 planId: {}, currentStopId: {}, candidateStopIds: {}, recommendedStopId: {}",
            planId, currentPoint.responseStopId, candidateIds, recommendedStopId,
        )
        return NextStopRecommendationResponse.available(
            currentPoint.responseStopId, recommendedStop, candidates.size, candidateIds,
            optimizedRoute.stopIds, estimatedTravelSeconds, kakaoTravelSeconds,
        )
    }

    private fun resolveCurrentPoint(plan: DeliveryPlan): RoutePoint {
        val completedStop = plan.deliveryStops
            .filter { it.status.isCompleted() && it.completedAt != null }
            .maxByOrNull { requireNotNull(it.completedAt) }
        return if (completedStop == null) {
            RoutePoint(DEPARTURE_NODE_ID, null, plan.departureLatitude, plan.departureLongitude)
        } else {
            RoutePoint(requireNotNull(completedStop.id), completedStop.id, completedStop.latitude, completedStop.longitude)
        }
    }

    private fun riskPriority(stop: DeliveryStop): RiskPriority {
        val assessment = stop.riskAssessment
        if (assessment.level == RiskLevel.UNKNOWN || assessment.score < 0) {
            return RiskPriority(3, Int.MAX_VALUE)
        }
        val levelOrder = when (assessment.level) {
            RiskLevel.SAFE -> 0
            RiskLevel.CAUTION -> 1
            RiskLevel.DANGER -> 2
            RiskLevel.UNKNOWN -> 3
        }
        return RiskPriority(levelOrder, assessment.score)
    }

    private fun createTravelCostMatrix(currentPoint: RoutePoint, candidates: List<DeliveryStop>): TravelCostMatrix {
        val points = listOf(currentPoint) + candidates.map {
            RoutePoint(requireNotNull(it.id), it.id, it.latitude, it.longitude)
        }
        val durations = buildMap {
            for (from in points) {
                for (to in points) {
                    if (from.nodeId != to.nodeId && to.nodeId != currentPoint.nodeId) {
                        put(RouteLeg(from.nodeId, to.nodeId), estimateTravelSeconds(from, to))
                    }
                }
            }
        }
        return TravelCostMatrix(durations)
    }

    private fun estimateTravelSeconds(from: RoutePoint, to: RoutePoint): Long {
        val latitudeDistance = (to.latitude - from.latitude) * DEGREES_TO_RADIANS
        val longitudeDistance = (to.longitude - from.longitude) * DEGREES_TO_RADIANS
        val fromLatitude = from.latitude * DEGREES_TO_RADIANS
        val toLatitude = to.latitude * DEGREES_TO_RADIANS
        val haversine = sin(latitudeDistance / 2).pow(2) +
            cos(fromLatitude) * cos(toLatitude) * sin(longitudeDistance / 2).pow(2)
        val distanceMeters = 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine))
        return ceil(distanceMeters / ESTIMATED_SPEED_METERS_PER_SECOND).toLong().coerceAtLeast(1)
    }

    private data class RoutePoint(
        val nodeId: Long,
        val responseStopId: Long?,
        val latitude: Double,
        val longitude: Double,
    )

    private data class RiskPriority(val levelOrder: Int, val score: Int)

    private companion object {
        const val CANDIDATE_LIMIT = 5
        const val DEPARTURE_NODE_ID = Long.MIN_VALUE
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val ESTIMATED_SPEED_METERS_PER_SECOND = 30_000.0 / 3_600.0
        const val DEGREES_TO_RADIANS = PI / 180.0
    }
}
