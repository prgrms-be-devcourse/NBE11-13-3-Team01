package com.example.delivery_project.util

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 좌표 기반 직선 거리 및 예상 이동 시간 계산.
 * 다음 배송지 추천과 배송 기사 추천이 동일한 기준을 사용하도록 한 곳에 모았다.
 */
object GeoDistance {
    private const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val DEGREES_TO_RADIANS = PI / 180.0

    /** 도심 평균 주행 속도 가정값 (30km/h) */
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
