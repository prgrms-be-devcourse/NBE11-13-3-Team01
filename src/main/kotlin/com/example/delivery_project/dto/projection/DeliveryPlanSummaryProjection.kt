package com.example.delivery_project.dto.projection

import java.time.LocalDateTime

interface DeliveryPlanSummaryProjection {
    val planId: Long
    val driverId: Long
    val driverLoginId: String
    val driverName: String
    val departureLocation: String
    val scheduledDepartureAt: LocalDateTime
    val actualDepartureAt: LocalDateTime?
    val completedAt: LocalDateTime?
    val status: String
    val totalStops: Number
    val remainingStops: Number
    val totalBoxes: Number
    val remainingBoxes: Number
    val dangerStops: Number
}
