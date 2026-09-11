package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan

data class AdminDeliveryPlanDetailResponse(
    val driverId: Long?,
    val driverLoginId: String,
    val driverName: String,
    val deliveryPlan: DeliveryPlanDetailResponse,
) {
    companion object {
        fun from(plan: DeliveryPlan) = AdminDeliveryPlanDetailResponse(
            plan.driver.id,
            plan.driver.loginId,
            plan.driver.name,
            DeliveryPlanDetailResponse.from(plan),
        )
    }
}
