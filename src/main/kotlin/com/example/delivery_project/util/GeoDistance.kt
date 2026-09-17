package com.example.delivery_project.util

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoDistance {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val DEGREES_TO_RADIANS = PI / 180.0

    const val DEFAULT_SPEED_METERS_PER_SECOND = 30_000.0 / 3_600.0

    fun haversineMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        val latitudeDistance = (toLatitude - fromLatitude) * DEGREES_TO_RADIANS
        val longitudeDistance = (toLongitude - fromLongitude) * DEGREES_TO_RADIANS
        val haversine = sin(latitudeDistance / 2).pow(2) +
            cos(fromLatitude * DEGREES_TO_RADIANS) * cos(toLatitude * DEGREES_TO_RADIANS) *
            sin(longitudeDistance / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine))
    }

    fun estimateTravelSeconds(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Long = estimateTravelSeconds(haversineMeters(fromLatitude, fromLongitude, toLatitude, toLongitude))

    fun estimateTravelSeconds(distanceMeters: Double): Long =
        ceil(distanceMeters / DEFAULT_SPEED_METERS_PER_SECOND).toLong().coerceAtLeast(1)
}
