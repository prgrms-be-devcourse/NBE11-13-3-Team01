package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.enums.DeliveryStopStatus
import java.time.LocalDateTime

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
