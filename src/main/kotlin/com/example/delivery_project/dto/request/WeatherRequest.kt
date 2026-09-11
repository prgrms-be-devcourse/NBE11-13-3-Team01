package com.example.delivery_project.dto.request

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "기상청 초단기예보 데이터 요청. 발표 날짜/시각을 기준으로 6시간 후까지의 데이터를 요청한다.")
data class WeatherRequest(
    @field:Schema(description = "발표 시각", example = "1430")
    val baseTime: String,
    @field:Schema(description = "발표 날짜", example = "20260101")
    val baseDate: String,
    @field:Schema(description = "기상청 격자 좌표", example = "60")
    val nx: Int,
    @field:Schema(description = "기상청 격자 좌표", example = "127")
    val ny: Int,
)
