package com.example.delivery_project.enums

enum class DeliveryPlanStatus {
    /** 관리자가 등록했지만 아직 배송 기사가 수령하지 않은 상태 */
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
        /** 기사에게 배정되어 진행 중인(= 동시 보유 수량에 포함되는) 상태 */
        val ACTIVE_STATUSES: List<DeliveryPlanStatus> = listOf(READY, DELIVERING)
    }
}
