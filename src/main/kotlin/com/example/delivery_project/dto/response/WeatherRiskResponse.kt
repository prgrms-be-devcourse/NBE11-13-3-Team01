package com.example.delivery_project.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "날씨 위험 요인 목록 응답")
data class WeatherRiskResponse(val factors: List<WeatherRiskFactorResponse>)
