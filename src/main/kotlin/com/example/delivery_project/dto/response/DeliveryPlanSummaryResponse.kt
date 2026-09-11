package com.example.delivery_project.dto.response

import com.example.delivery_project.dto.projection.DeliveryPlanSummaryProjection
import com.example.delivery_project.enums.DeliveryPlanStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

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
