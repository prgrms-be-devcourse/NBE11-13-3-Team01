package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.DeliveryItem
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.domain.entity.user.DriverLocation
import com.example.delivery_project.dto.projection.DeliveryPlanSummaryProjection
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.DeliveryStopStatus
import com.example.delivery_project.enums.ProductType
import com.example.delivery_project.enums.RiskLevel
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class CreateDeliveryPlanResponse(val planId: Long?)

@Schema(description = "배송 기사 현재 위치 응답")
data class DriverLocationResponse(
    val driverId: Long,
    val driverLoginId: String,
    val driverName: String,
    val latitude: Double,
    val longitude: Double,
    val updatedAt: LocalDateTime,
) {
    companion object {
        fun from(location: DriverLocation) = DriverLocationResponse(
            driverId = requireNotNull(location.driver.id),
            driverLoginId = location.driver.loginId,
            driverName = location.driver.name,
            latitude = location.latitude,
            longitude = location.longitude,
            updatedAt = location.updatedAt,
        )
    }
}

@Schema(description = "배송 계획 요약 응답")
data class DeliveryPlanSummaryResponse(
    val planId: Long,
    val departureLocation: String,
    val scheduledDepartureAt: LocalDateTime,
    val actualDepartureAt: LocalDateTime?,
    val completedAt: LocalDateTime?,
    val status: DeliveryPlanStatus,
    val totalStops: Int,
    val remainingStops: Long,
    val totalBoxes: Long,
    val remainingBoxes: Long,
    val dangerStops: Long,
) {
    companion object {
        fun from(summary: DeliveryPlanSummaryProjection) = DeliveryPlanSummaryResponse(
            summary.planId,
            summary.departureLocation,
            summary.scheduledDepartureAt,
            summary.actualDepartureAt,
            summary.completedAt,
            DeliveryPlanStatus.valueOf(summary.status),
            summary.totalStops.toInt(),
            summary.remainingStops.toLong(),
            summary.totalBoxes.toLong(),
            summary.remainingBoxes.toLong(),
            summary.dangerStops.toLong(),
        )
    }
}

data class DeliveryPlanDetailResponse(
    val planId: Long?,
    val departureLocation: String,
    val departureLatitude: Double,
    val departureLongitude: Double,
    val scheduledDepartureAt: LocalDateTime,
    val actualDepartureAt: LocalDateTime?,
    val status: DeliveryPlanStatus,
    val completedAt: LocalDateTime?,
    val deliveryStops: List<DeliveryStopResponse>,
) {
    companion object {
        fun from(plan: DeliveryPlan) = DeliveryPlanDetailResponse(
            plan.id,
            plan.departureLocation,
            plan.departureLatitude,
            plan.departureLongitude,
            plan.scheduledDepartureAt,
            plan.actualDepartureAt,
            plan.status,
            plan.completedAt,
            plan.deliveryStops.map(DeliveryStopResponse::from),
        )
    }
}

data class DeliveryStopResponse(
    val stopId: Long?,
    val status: DeliveryStopStatus,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val completedAt: LocalDateTime?,
    val riskAssessment: RiskAssessmentResponse,
    val deliveryItems: List<DeliveryItemResponse>,
) {
    companion object {
        fun from(stop: DeliveryStop) = DeliveryStopResponse(
            stop.id,
            stop.status,
            stop.address,
            stop.latitude,
            stop.longitude,
            stop.completedAt,
            RiskAssessmentResponse.from(stop.riskAssessment),
            stop.deliveryItems.map(DeliveryItemResponse::from),
        )
    }
}

@Schema(description = "배송 상품 응답")
data class DeliveryItemResponse(
    @field:Schema(description = "상품 id")
    val itemId: Long?,
    @field:Schema(description = "상품명", example = "생수1L")
    val productName: String,
    @field:Schema(description = "상품 종류", example = "FRAGILE")
    val productType: ProductType,
    @field:Schema(description = "상품 개수", example = "10")
    val quantity: Int,
) {
    companion object {
        fun from(item: DeliveryItem) = DeliveryItemResponse(
            item.id, item.productName, item.productType, item.quantity,
        )
    }
}

data class NextStopRecommendationResponse(
    val available: Boolean,
    val currentStopId: Long?,
    val recommendedStopId: Long?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val riskLevel: RiskLevel?,
    val riskScore: Int?,
    val candidateCount: Int,
    val candidateStopIds: List<Long>,
    val optimizedSafestRouteStopIds: List<Long>,
    val estimatedTravelSeconds: Long?,
    val kakaoTravelSeconds: Long?,
) {
    companion object {
        fun unavailable(currentStopId: Long?) = NextStopRecommendationResponse(
            false, currentStopId, null, null, null, null, null, null,
            0, emptyList(), emptyList(), null, null,
        )

        fun available(
            currentStopId: Long?,
            recommendedStop: DeliveryStop,
            candidateCount: Int,
            candidateStopIds: List<Long>,
            optimizedSafestRouteStopIds: List<Long>,
            estimatedTravelSeconds: Long,
            kakaoTravelSeconds: Long?,
        ) = NextStopRecommendationResponse(
            true,
            currentStopId,
            recommendedStop.id,
            recommendedStop.address,
            recommendedStop.latitude,
            recommendedStop.longitude,
            recommendedStop.riskAssessment.level,
            recommendedStop.riskAssessment.score,
            candidateCount,
            candidateStopIds.toList(),
            optimizedSafestRouteStopIds.toList(),
            estimatedTravelSeconds,
            kakaoTravelSeconds,
        )
    }
}
