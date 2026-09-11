package com.example.delivery_project.service.component

import org.springframework.stereotype.Component

@Component
class RiskFactorCalculator {
    private val numberPattern = Regex("\\d+(?:\\.\\d+)?")

    fun isHeavyRain(rn1: String?, pty: String?): Boolean {
        if (!isRain(pty) || rn1.isNullOrBlank() || rn1 == "강수없음") return false
        val minimumRainfall = numberPattern.find(rn1)?.value?.toDoubleOrNull() ?: return false
        return minimumRainfall >= 30.0
    }

    fun isHeatWave(t1h: String?): Boolean = t1h?.toDoubleOrNull()?.let { it >= 33 } ?: false

    fun isRain(pty: String?): Boolean = pty?.toIntOrNull() in setOf(1, 4, 5)
}
