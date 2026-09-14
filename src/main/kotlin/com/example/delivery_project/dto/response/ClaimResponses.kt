package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.dto.projection.OpenDeliveryPlanSummaryProjection
import com.example.delivery_project.enums.DeliveryPlanStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "배송 업무 수령 응답")
data class ClaimDeliveryPlanResponse(
    @field:Schema(description = "배송 계획 ID")
    val planId: Long?,
    @field:Schema(description = "수령한 배송 기사 ID")
    val driverId: Long?,
    @field:Schema(description = "수령 후 배송 계획 상태")
    val status: DeliveryPlanStatus,
    @field:Schema(description = "수령 시각")
    val assignedAt: LocalDateTime?,
    @field:Schema(description = "이미 본인이 수령한 업무를 다시 요청한 경우 true (멱등 응답)")
    val alreadyOwned: Boolean,
    @field:Schema(description = "수령 후 기사가 보유 중인 진행 중 업무 수")
    val activePlanCount: Long,
) {
    companion object {
        fun from(plan: DeliveryPlan, alreadyOwned: Boolean, activePlanCount: Long) = ClaimDeliveryPlanResponse(
            planId = plan.id,
            driverId = plan.driver?.id,
            status = plan.status,
            assignedAt = plan.assignedAt,
            alreadyOwned = alreadyOwned,
            activePlanCount = activePlanCount,
        )
    }
}

@Schema(description = "미배정 배송 업무 응답. 우선 수령 윈도우 정보를 포함한다.")
data class OpenDeliveryPlanResponse(
    val planId: Long,
    val departureLocation: String,
    val scheduledDepartureAt: LocalDateTime,
    val status: DeliveryPlanStatus,
    val totalStops: Int,
    val remainingStops: Long,
    val totalBoxes: Long,
    val remainingBoxes: Long,
    val dangerStops: Long,
    @field:Schema(description = "전체 기사에게 공개되는 시각. null 이면 처음부터 전체 공개된 업무다.")
    val publicAt: LocalDateTime?,
    @field:Schema(description = "요청한 기사의 추천 우선권 순위. 우선권이 없으면 null.")
    val priorityRank: Int?,
    @field:Schema(description = "요청한 기사가 지금 수령할 수 있는지 여부")
    val claimableNow: Boolean,
) {
    companion object {
        fun from(summary: OpenDeliveryPlanSummaryProjection, now: LocalDateTime): OpenDeliveryPlanResponse {
            val publicAt = summary.publicAt
            val priorityRank = summary.priorityRank
            return OpenDeliveryPlanResponse(
                planId = summary.planId,
                departureLocation = summary.departureLocation,
                scheduledDepartureAt = summary.scheduledDepartureAt,
                status = DeliveryPlanStatus.valueOf(summary.status),
                totalStops = summary.totalStops.toInt(),
                remainingStops = summary.remainingStops.toLong(),
                totalBoxes = summary.totalBoxes.toLong(),
                remainingBoxes = summary.remainingBoxes.toLong(),
                dangerStops = summary.dangerStops.toLong(),
                publicAt = publicAt,
                priorityRank = priorityRank,
                // 우선권이 있으면 공개 전에도, 없으면 공개 시각 이후에만 수령할 수 있다.
                claimableNow = priorityRank != null || publicAt == null || !now.isBefore(publicAt),
            )
        }
    }
}
