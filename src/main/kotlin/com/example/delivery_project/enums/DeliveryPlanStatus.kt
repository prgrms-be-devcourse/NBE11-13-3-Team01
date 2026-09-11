package com.example.delivery_project.enums

enum class DeliveryPlanStatus {
    READY,
    DELIVERING,
    COMPLETED,
    ;

    fun isReady(): Boolean = this == READY

    fun isDelivering(): Boolean = this == DELIVERING

    fun isCompleted(): Boolean = this == COMPLETED
}
