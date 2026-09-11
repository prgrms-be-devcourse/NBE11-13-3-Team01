package com.example.delivery_project.enums

enum class RiskFactorType(
    val description: String,
    val riskScore: Int,
) {
    HEAVY_RAIN("폭우", 30),
    HEAT_WAVE("폭염", 20),
    WEATHER_WARNING("기상 특보", 40),
}
