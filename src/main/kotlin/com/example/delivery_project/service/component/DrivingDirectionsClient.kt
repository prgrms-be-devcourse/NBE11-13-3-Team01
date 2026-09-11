package com.example.delivery_project.service.component


interface DrivingDirectionsClient {
    fun findTravelDurationSeconds(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Long?
}
