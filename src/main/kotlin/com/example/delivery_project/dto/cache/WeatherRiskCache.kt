package com.example.delivery_project.dto.cache

import java.time.LocalDateTime

data class WeatherRiskCache(
    val forecastAt: LocalDateTime,
    val t1h: String,
    val rn1: String,
    val pty: String,
) {
    fun toValues(): Map<String, String> = mapOf(
        "T1H" to t1h,
        "RN1" to rn1,
        "PTY" to pty,
    )
}
