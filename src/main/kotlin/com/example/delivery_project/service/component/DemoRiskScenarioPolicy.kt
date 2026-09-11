package com.example.delivery_project.service.component

import com.example.delivery_project.domain.entity.delivery.RiskAssessment
import com.example.delivery_project.enums.RiskFactorType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class DemoRiskScenarioPolicy(@param:Value("\${demo.risk.enabled:false}") private val enabled: Boolean) {
    fun applyIfEnabled(stopId: Long, assessment: RiskAssessment, analyzedAt: LocalDateTime): Boolean {
        if (!enabled) return false
        when ((stopId - 1).mod(5)) {
            0 -> assessment.replaceFactors(emptyList(), analyzedAt)
            1 -> assessment.replaceFactors(listOf(RiskFactorType.HEAT_WAVE), analyzedAt)
            2 -> assessment.replaceFactors(listOf(RiskFactorType.WEATHER_WARNING), analyzedAt)
            3 -> assessment.replaceFactors(listOf(RiskFactorType.HEAVY_RAIN, RiskFactorType.WEATHER_WARNING), analyzedAt)
            4 -> assessment.markUnknown(analyzedAt)
        }
        return true
    }
}
