package com.example.delivery_project.controller

import com.example.delivery_project.dto.request.UpdateDeliveryOrderRequest
import com.example.delivery_project.dto.response.DeliveryPlanDetailResponse
import com.example.delivery_project.dto.response.DeliveryPlanSummaryResponse
import com.example.delivery_project.dto.response.DeliveryStopResponse
import com.example.delivery_project.dto.response.NextStopRecommendationResponse
import com.example.delivery_project.security.auth.CustomUserDetails
import com.example.delivery_project.service.DeliveryPlanService
import com.example.delivery_project.service.NextStopRecommendationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/delivery-plans")
@PreAuthorize("hasRole('DELIVERY_DRIVER')")
@Tag(name = "배송 계획", description = "배송 기사의 배송 계획 조회 및 진행 관리 API")
class DeliveryPlanController(
    private val deliveryPlanService: DeliveryPlanService,
    private val nextStopRecommendationService: NextStopRecommendationService,
) {
    @Operation(summary = "내 배송 계획 목록 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송 계획 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "배송 기사 권한 필요"),
    ])
    @GetMapping
    fun getMyDeliveryPlans(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): List<DeliveryPlanSummaryResponse> =
        deliveryPlanService.getDeliveryPlans(requireNotNull(userDetails.user.id))

    @Operation(summary = "배송 계획 상세 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송 계획 상세 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "접근 권한 없음"),
        ApiResponse(responseCode = "404", description = "배송 계획을 찾을 수 없음"),
    ])
    @GetMapping("/{planId}")
    fun getDeliveryPlan(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
    ): DeliveryPlanDetailResponse =
        deliveryPlanService.getDeliveryPlan(planId, requireNotNull(userDetails.user.id))

    @Operation(summary = "배송지 상세 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송지 상세 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "접근 권한 없음"),
        ApiResponse(responseCode = "404", description = "배송 계획 또는 배송지를 찾을 수 없음"),
    ])
    @GetMapping("/{planId}/stops/{stopId}")
    fun getDeliveryStop(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
        @Parameter(description = "배송지 ID", example = "1") @PathVariable stopId: Long,
    ): DeliveryStopResponse =
        deliveryPlanService.getDeliveryStop(planId, stopId, requireNotNull(userDetails.user.id))

    @Operation(
        summary = "다음 배송지 추천",
        description = "배송 중 남아 있는 순서의 최대 5개 배송지 중 위험도가 가장 낮은 후보를 우선하고, 같은 위험도 후보는 좌표 기반 예상 이동시간 다익스트라 경로로 추천합니다.",
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "다음 배송지 추천 성공"),
        ApiResponse(responseCode = "400", description = "배송 중인 계획이 아님"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "접근 권한 없음"),
        ApiResponse(responseCode = "404", description = "배송 계획을 찾을 수 없음"),
    ])
    @GetMapping("/{planId}/next-stop-recommendation")
    fun recommendNextStop(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
    ): NextStopRecommendationResponse =
        nextStopRecommendationService.recommend(planId, requireNotNull(userDetails.user.id))

    @Operation(summary = "배송 순서 변경", description = "READY 상태인 배송 계획의 배송지 방문 순서를 변경합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "배송 순서 변경 성공"),
        ApiResponse(responseCode = "400", description = "요청 값 또는 배송 계획 상태가 올바르지 않음"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "접근 권한 없음"),
        ApiResponse(responseCode = "404", description = "배송 계획을 찾을 수 없음"),
    ])
    @PutMapping("/{planId}/stops/order")
    fun reorderStops(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
        @Valid @RequestBody request: UpdateDeliveryOrderRequest,
    ): ResponseEntity<Void> {
        deliveryPlanService.reorderStops(planId, requireNotNull(userDetails.user.id), request)
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "배송 시작", description = "READY 상태인 배송 계획을 DELIVERING 상태로 변경합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "배송 시작 성공"),
        ApiResponse(responseCode = "400", description = "배송 계획 상태가 올바르지 않음"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "접근 권한 없음"),
        ApiResponse(responseCode = "404", description = "배송 계획을 찾을 수 없음"),
    ])
    @PostMapping("/{planId}/start")
    fun start(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
    ): ResponseEntity<Void> {
        deliveryPlanService.start(planId, requireNotNull(userDetails.user.id))
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "배송지 완료", description = "배송 중인 배송지를 COMPLETED 상태로 변경합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "배송지 완료 처리 성공"),
        ApiResponse(responseCode = "400", description = "배송 계획 또는 배송지 상태가 올바르지 않음"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "접근 권한 없음"),
        ApiResponse(responseCode = "404", description = "배송 계획 또는 배송지를 찾을 수 없음"),
    ])
    @PostMapping("/{planId}/stops/{stopId}/complete")
    fun completeStop(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
        @Parameter(description = "배송지 ID", example = "1") @PathVariable stopId: Long,
    ): ResponseEntity<Void> {
        deliveryPlanService.completeStop(planId, stopId, requireNotNull(userDetails.user.id))
        return ResponseEntity.noContent().build()
    }

    @Operation(summary = "배송 계획 완료", description = "모든 배송지가 완료된 배송 계획을 COMPLETED 상태로 변경합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "배송 계획 완료 처리 성공"),
        ApiResponse(responseCode = "400", description = "배송 계획 또는 배송지 상태가 올바르지 않음"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "접근 권한 없음"),
        ApiResponse(responseCode = "404", description = "배송 계획을 찾을 수 없음"),
    ])
    @PostMapping("/{planId}/complete")
    fun completePlan(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
    ): ResponseEntity<Void> {
        deliveryPlanService.completePlan(planId, requireNotNull(userDetails.user.id))
        return ResponseEntity.noContent().build()
    }
}
