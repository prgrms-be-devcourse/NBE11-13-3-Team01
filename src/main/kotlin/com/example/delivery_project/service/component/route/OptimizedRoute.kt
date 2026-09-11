package com.example.delivery_project.service.component.route

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

data class OptimizedRoute(
    val stopIds: PersistentList<Long>,
    val totalDurationSeconds: Long,
    val expandedStateCount: Int,
) {
    constructor(stopIds: List<Long>, totalDurationSeconds: Long, expandedStateCount: Int) :
        this(stopIds.toPersistentList(), totalDurationSeconds, expandedStateCount)
}
