package com.example.delivery_project.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotEmpty

@Schema(description = "배송 순서 업데이트 요청")
data class UpdateDeliveryOrderRequest(
    @field:Schema(description = "순서를 변경할 배송지 id 목록")
    @field:NotEmpty(message = "배송지 순서는 비어 있을 수 없습니다.")
    val stopIds: List<Long> = emptyList(),
)
