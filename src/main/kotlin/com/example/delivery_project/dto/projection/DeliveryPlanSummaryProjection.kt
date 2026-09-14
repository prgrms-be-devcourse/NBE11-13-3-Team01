package com.example.delivery_project.dto.projection

import java.time.LocalDateTime

interface DeliveryPlanSummaryProjection {
    val planId: Long

    /** 미배정(OPEN) 업무는 수령한 기사가 없으므로 null 이다. */
    val driverId: Long?
    val driverLoginId: String?
    val driverName: String?
    val departureLocation: String
    val scheduledDepartureAt: LocalDateTime
    val assignedAt: LocalDateTime?
    val actualDepartureAt: LocalDateTime?
    val completedAt: LocalDateTime?
    val status: String
    val totalStops: Number
    val remainingStops: Number
    val totalBoxes: Number
    val remainingBoxes: Number
    val dangerStops: Number
}

/**
 * 미배정 업무 목록에 우선 수령 정보를 덧붙인 프로젝션.
 * [priorityRank] 는 조회한 기사 본인의 우선권 순위이며, 우선권이 없으면 null 이다.
 */
interface OpenDeliveryPlanSummaryProjection : DeliveryPlanSummaryProjection {
    val publicAt: LocalDateTime?
    val priorityRank: Int?
}

interface DeliveryStatisticsProjection {
    val totalPlans: Number
    val openPlans: Number
    val readyPlans: Number
    val deliveringPlans: Number
    val completedPlans: Number
    val totalStops: Number
    val remainingStops: Number
    val totalBoxes: Number
    val remainingBoxes: Number
    val dangerStops: Number
}

/**
 * 배송 업무 추천 스코어링에 사용하는 기사별 현재 업무량 집계.
 * READY / DELIVERING 상태의 계획만 집계 대상이다.
 */
interface DriverWorkloadProjection {
    val driverId: Long
    val driverLoginId: String
    val driverName: String
    val activePlans: Number
    val remainingStops: Number
    val remainingBoxes: Number
    val dangerStops: Number
}
