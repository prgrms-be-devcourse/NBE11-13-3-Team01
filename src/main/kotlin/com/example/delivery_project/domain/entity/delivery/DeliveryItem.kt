package com.example.delivery_project.domain.entity.delivery

import com.example.delivery_project.enums.ProductType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive

@Entity
class DeliveryItem private constructor(
    deliveryStop: DeliveryStop,
    productName: String,
    productType: ProductType,
    quantity: Int,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "delivery_stop_id", nullable = false)
    var deliveryStop: DeliveryStop = deliveryStop
        protected set

    @field:Column(nullable = false)
    @field:NotBlank
    var productName: String = productName
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var productType: ProductType = productType
        protected set

    @field:Column(nullable = false)
    @field:Positive
    var quantity: Int = quantity
        protected set

    companion object {
        internal fun of(
            deliveryStop: DeliveryStop,
            productName: String,
            productType: ProductType,
            quantity: Int,
        ): DeliveryItem = DeliveryItem(deliveryStop, productName, productType, quantity)
    }
}
