package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.DeliveryItem
import com.example.delivery_project.enums.ProductType
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "배송 상품 응답")
data class DeliveryItemResponse(
    @field:Schema(description = "상품 id")
    val itemId: Long?,
    @field:Schema(description = "상품명", example = "생수1L")
    val productName: String,
    @field:Schema(description = "상품 종류", example = "FRAGILE")
    val productType: ProductType,
    @field:Schema(description = "상품 개수", example = "10")
    val quantity: Int,
) {
    companion object {
        fun from(item: DeliveryItem) = DeliveryItemResponse(
            item.id, item.productName, item.productType, item.quantity,
        )
    }
}
