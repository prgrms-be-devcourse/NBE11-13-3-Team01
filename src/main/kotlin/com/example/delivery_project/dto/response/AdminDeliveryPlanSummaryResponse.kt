package com.example.delivery_project.dto.response

import com.example.delivery_project.dto.projection.DeliveryPlanSummaryProjection
import com.example.delivery_project.enums.DeliveryPlanStatus
import java.time.LocalDateTime

data class AdminDeliveryPlanSummaryResponse(
    val planId: Long?,
    val driverId: Long?,
    val driverLoginId: String,
    val driverName: String,
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
        fun from(summary: DeliveryPlanSummaryProjection) = AdminDeliveryPlanSummaryResponse(
            summary.planId, summary.driverId, summary.driverLoginId, summary.driverName,
            summary.departureLocation, summary.scheduledDepartureAt, summary.actualDepartureAt,
            summary.completedAt, DeliveryPlanStatus.valueOf(summary.status), summary.totalStops.toInt(),
            summary.remainingStops.toLong(), summary.totalBoxes.toLong(), summary.remainingBoxes.toLong(),
            summary.dangerStops.toLong(),
        )
    }
}
