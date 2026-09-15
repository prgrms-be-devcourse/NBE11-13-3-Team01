package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.RiskAssessment
import com.example.delivery_project.domain.entity.delivery.RiskFactor
import com.example.delivery_project.enums.RiskFactorType
import com.example.delivery_project.enums.RiskLevel
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

data class RiskAssessmentResponse(
    val score: Int,
    val level: RiskLevel,
    val analyzedAt: LocalDateTime,
    val factors: List<RiskFactorResponse>,
) {
    companion object {
        fun from(assessment: RiskAssessment) = RiskAssessmentResponse(
            assessment.score,
            assessment.level,
            assessment.analyzedAt,
            assessment.riskFactors.map(RiskFactorResponse::from),
        )
    }
}

data class RiskFactorResponse(
    val type: RiskFactorType,
    val description: String?,
    val score: Int,
) {
    companion object {
        fun from(factor: RiskFactor) = RiskFactorResponse(factor.type, factor.description, factor.riskScore)
    }
}

@Schema(description = "날씨 위험 요인 목록 응답")
data class WeatherRiskResponse(val factors: List<WeatherRiskFactorResponse>)

@Schema(description = "날씨 위험 요인 응답")
data class WeatherRiskFactorResponse(
    @field:Schema(description = "위험 요인 종류", example = "HEAVY_RAIN")
    val type: RiskFactorType,
    @field:Schema(description = "위험 요인 상세", example = "폭우")
    val description: String,
)
