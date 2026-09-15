package com.example.delivery_project.dto.request

import com.example.delivery_project.enums.ProductType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
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

@Schema(description = "배송 상품 생성 요청")
data class CreateDeliveryItemRequest(
    @field:Schema(description = "상품명", example = "생수1L")
    @field:NotBlank(message = "상품명은 필수입니다.")
    val productName: String = "",
    @field:Schema(description = "상품 종류", example = "FRAGILE")
    @field:NotNull(message = "상품 유형은 필수입니다.")
    val productType: ProductType?,
    @field:Schema(description = "상품 개수", example = "10")
    @field:NotNull(message = "상품 수량은 필수입니다.")
    @field:Positive(message = "상품 수량은 1개 이상이어야 합니다.")
    val quantity: Int?,
)

@Schema(description = "배송 순서 업데이트 요청")
data class UpdateDeliveryOrderRequest(
    @field:Schema(description = "순서를 변경할 배송지 id 목록")
    @field:NotEmpty(message = "배송지 순서는 비어 있을 수 없습니다.")
    val stopIds: List<Long> = emptyList(),
)

@Schema(description = "배송 예상 출발 시각 업데이트 요청")
data class UpdateScheduledDepartureRequest(
    @field:Schema(description = "배송 예상 출발 시각", example = "2026-01-01T00:00:00")
    @field:NotNull
    val scheduledDepartureAt: LocalDateTime?,
)

@Schema(description = "배송 기사 현재 위치 갱신 요청")
data class UpdateDriverLocationRequest(
    @field:Schema(description = "위도", example = "37.5665")
    @field:NotNull(message = "위도는 필수입니다.")
    @field:DecimalMin(value = "-90.0", message = "위도는 -90 이상이어야 합니다.")
    @field:DecimalMax(value = "90.0", message = "위도는 90 이하여야 합니다.")
    val latitude: Double?,
    @field:Schema(description = "경도", example = "126.9780")
    @field:NotNull(message = "경도는 필수입니다.")
    @field:DecimalMin(value = "-180.0", message = "경도는 -180 이상이어야 합니다.")
    @field:DecimalMax(value = "180.0", message = "경도는 180 이하여야 합니다.")
    val longitude: Double?,
)
