package com.example.delivery_project.service.component.route

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

data class RouteOptimizationContext(
    val currentStopId: Long,
    val candidateStopIds: PersistentList<Long>,
    val travelCostMatrix: TravelCostMatrix,
) {
    constructor(currentStopId: Long, candidateStopIds: List<Long>, travelCostMatrix: TravelCostMatrix) :
        this(currentStopId, candidateStopIds.toPersistentList(), travelCostMatrix)

    init {
        require(candidateStopIds.distinct().size == candidateStopIds.size) { "후보 배송지 ID는 중복될 수 없습니다." }
        require(currentStopId !in candidateStopIds) { "현재 배송지는 후보 배송지에 포함될 수 없습니다." }
    }
}
