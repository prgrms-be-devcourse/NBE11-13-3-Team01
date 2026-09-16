package com.example.delivery_project.enums

enum class DeliveryPlanStatus {
    OPEN,
    READY,
    DELIVERING,
    COMPLETED,
    ;

    fun isOpen(): Boolean = this == OPEN

    fun isReady(): Boolean = this == READY

    fun isDelivering(): Boolean = this == DELIVERING

    fun isCompleted(): Boolean = this == COMPLETED

    companion object {
        val ACTIVE_STATUSES: List<DeliveryPlanStatus> = listOf(READY, DELIVERING)
    }
}
