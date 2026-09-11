package com.example.delivery_project.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

@Schema(description = "배송지 생성 요청")
class CreateDeliveryStopRequest(
    @field:Schema(description = "배송주소", example = "서울특별시 서초구 반포대로 45, 명정빌딩 4층")
    @field:NotBlank(message = "배송지 주소는 필수입니다.")
    val address: String = "",
    items: List<CreateDeliveryItemRequest>? = null,
) {
    @field:Schema(description = "배송 상품 목록")
    @field:NotEmpty(message = "배송 상품은 한 개 이상이어야 합니다.")
    val items: List<@Valid CreateDeliveryItemRequest> = items?.toList().orEmpty()
}
