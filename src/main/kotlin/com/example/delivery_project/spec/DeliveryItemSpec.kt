package com.example.delivery_project.spec

import com.example.delivery_project.enums.ProductType

data class DeliveryItemSpec(
    val productName: String,
    val productType: ProductType,
    val quantity: Int,
)
