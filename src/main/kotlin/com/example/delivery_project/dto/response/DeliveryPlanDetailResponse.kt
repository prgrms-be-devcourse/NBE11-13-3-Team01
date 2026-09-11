package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.enums.DeliveryPlanStatus
import java.time.LocalDateTime

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
