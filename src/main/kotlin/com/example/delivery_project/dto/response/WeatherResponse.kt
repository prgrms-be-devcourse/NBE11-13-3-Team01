package com.example.delivery_project.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "기상청 초단기예보 데이터 응답")
data class WeatherResponse(val header: Header?, val body: Body?) {
    data class Header(val resultCode: String?, val resultMsg: String?)
    data class Body(
        val dataType: String?,
        val items: Items?,
        val pageNo: Int?,
        val numOfRows: Int?,
        val totalCount: Int?,
    )
    data class Items(val item: List<Item>?)
    data class Item(
        @field:Schema(description = "발표 날짜", example = "20260101")
        val baseDate: String?,
        @field:Schema(description = "발표 시각", example = "1430")
        val baseTime: String?,
        @field:Schema(description = "카테고리", example = "T1H")
        val category: String?,
        @field:Schema(description = "예보 날짜", example = "20260101")
        val fcstDate: String?,
        @field:Schema(description = "예보 시각", example = "1430")
        val fcstTime: String?,
        @field:Schema(description = "카테고리 값", example = "강수없음")
        val fcstValue: String?,
        @field:Schema(description = "예보 위치 nx 격자 좌표", example = "60")
        val nx: Int?,
        @field:Schema(description = "예보 위치 ny 격자 좌표", example = "127")
        val ny: Int?,
    )
}
