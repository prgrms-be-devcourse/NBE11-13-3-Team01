package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.dto.projection.DeliveryPlanSummaryProjection
import com.example.delivery_project.dto.projection.DeliveryStatisticsProjection
import com.example.delivery_project.enums.DeliveryPlanStatus
import java.time.LocalDateTime

data class AdminDeliveryPlanDetailResponse(
    val driverId: Long?,
    val driverLoginId: String?,
    val driverName: String?,
    val deliveryPlan: DeliveryPlanDetailResponse,
) {
    companion object {
        fun from(plan: DeliveryPlan) = AdminDeliveryPlanDetailResponse(
            plan.driver?.id,
            plan.driver?.loginId,
            plan.driver?.name,
            DeliveryPlanDetailResponse.from(plan),
        )
    }
}

data class AdminDeliveryPlanSummaryResponse(
    val planId: Long?,
    val driverId: Long?,
    val driverLoginId: String?,
    val driverName: String?,
    val departureLocation: String,
    val scheduledDepartureAt: LocalDateTime,
    val assignedAt: LocalDateTime?,
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
            summary.departureLocation, summary.scheduledDepartureAt, summary.assignedAt,
            summary.actualDepartureAt, summary.completedAt, DeliveryPlanStatus.valueOf(summary.status),
            summary.totalStops.toInt(), summary.remainingStops.toLong(), summary.totalBoxes.toLong(),
            summary.remainingBoxes.toLong(), summary.dangerStops.toLong(),
        )
    }
}

data class DriverSummaryResponse(
    val driverId: Long?,
    val loginId: String,
    val name: String,
) {
    companion object {
        fun from(driver: User) = DriverSummaryResponse(driver.id, driver.loginId, driver.name)
    }
}

data class AdminDeliveryStatisticsResponse(
    val totalPlans: Long,
    val openPlans: Long,
    val readyPlans: Long,
    val deliveringPlans: Long,
    val completedPlans: Long,
    val totalStops: Long,
    val remainingStops: Long,
    val completedStops: Long,
    val totalBoxes: Long,
    val remainingBoxes: Long,
    val deliveredBoxes: Long,
    val dangerStops: Long,
) {
    companion object {
        fun from(statistics: DeliveryStatisticsProjection): AdminDeliveryStatisticsResponse {
            val totalStops = statistics.totalStops.toLong()
            val remainingStops = statistics.remainingStops.toLong()
            val totalBoxes = statistics.totalBoxes.toLong()
            val remainingBoxes = statistics.remainingBoxes.toLong()

            return AdminDeliveryStatisticsResponse(
                totalPlans = statistics.totalPlans.toLong(),
                openPlans = statistics.openPlans.toLong(),
                readyPlans = statistics.readyPlans.toLong(),
                deliveringPlans = statistics.deliveringPlans.toLong(),
                completedPlans = statistics.completedPlans.toLong(),
                totalStops = totalStops,
                remainingStops = remainingStops,
                completedStops = totalStops - remainingStops,
                totalBoxes = totalBoxes,
                remainingBoxes = remainingBoxes,
                deliveredBoxes = totalBoxes - remainingBoxes,
                dangerStops = statistics.dangerStops.toLong(),
            )
        }
    }
}
