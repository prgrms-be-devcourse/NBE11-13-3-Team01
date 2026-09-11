package com.example.delivery_project.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.time.LocalDateTime

@Schema(description = "배송 계획 생성 요청")
class CreateDeliveryPlanRequest(
    @field:Schema(description = "배송", example = "서울특별시 서초구 반포대로 45, 명정빌딩 4층")
    @field:NotBlank(message = "출발지 주소는 필수입니다.")
    val departureAddress: String = "",
    @field:Schema(description = "예상 출발 시각", example = "2026-01-01T00:00:00")
    @field:NotNull(message = "예정 출발 시각은 필수입니다.")
    val scheduledDepartureAt: LocalDateTime?,
    stops: List<CreateDeliveryStopRequest>? = null,
) {
    @field:Schema(description = "배송지 리스트")
    @field:NotEmpty(message = "배송지는 한 곳 이상이어야 합니다.")
    val stops: List<@Valid CreateDeliveryStopRequest> = stops?.toList().orEmpty()
}
