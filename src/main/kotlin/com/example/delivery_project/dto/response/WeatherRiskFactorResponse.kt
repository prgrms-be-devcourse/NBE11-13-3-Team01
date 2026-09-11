package com.example.delivery_project.dto.response

import com.example.delivery_project.enums.RiskFactorType
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "날씨 위험 요인 응답")
data class WeatherRiskFactorResponse(
    @field:Schema(description = "위험 요인 종류", example = "HEAVY_RAIN")
    val type: RiskFactorType,
    @field:Schema(description = "위험 요인 상세", example = "폭우")
    val description: String,
)
