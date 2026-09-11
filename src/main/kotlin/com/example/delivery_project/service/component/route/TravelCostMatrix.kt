package com.example.delivery_project.service.component.route

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentMap

data class TravelCostMatrix(val travelDurationSeconds: PersistentMap<RouteLeg, Long>) {
    constructor(travelDurationSeconds: Map<RouteLeg, Long>) : this(travelDurationSeconds.toPersistentMap())

    init {
        require(travelDurationSeconds.values.none { it < 0 }) { "이동시간은 음수일 수 없습니다." }
    }

    fun findDuration(fromStopId: Long, toStopId: Long): Long? = travelDurationSeconds[RouteLeg(fromStopId, toStopId)]
}
