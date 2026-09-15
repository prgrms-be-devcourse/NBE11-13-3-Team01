package com.example.delivery_project.controller

import com.example.delivery_project.dto.request.CreateDeliveryPlanRequest
import com.example.delivery_project.dto.response.AdminDeliveryPlanDetailResponse
import com.example.delivery_project.dto.response.AdminDeliveryPlanSummaryResponse
import com.example.delivery_project.dto.response.AdminDeliveryStatisticsResponse
import com.example.delivery_project.dto.response.CreateDeliveryPlanResponse
import com.example.delivery_project.dto.response.DriverRecommendationResponse
import com.example.delivery_project.service.AdminDeliveryPlanService
import com.example.delivery_project.service.DeliveryPlanCreationFacade
import com.example.delivery_project.service.DriverRecommendationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "관리자 배송 계획", description = "전체 배송 계획 조회 및 배송 기사 할당 API")
class AdminDeliveryPlanController(
    private val adminDeliveryPlanService: AdminDeliveryPlanService,
    private val deliveryPlanCreationFacade: DeliveryPlanCreationFacade,
    private val driverRecommendationService: DriverRecommendationService,
) {
    @Operation(summary = "전체 배송 계획 목록 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "전체 배송 계획 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
    ])
    @GetMapping("/delivery-plans")
    fun getAllDeliveryPlans(): List<AdminDeliveryPlanSummaryResponse> =
        adminDeliveryPlanService.getAllDeliveryPlans()

    @Operation(summary = "배송 계획 상세 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송 계획 상세 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
        ApiResponse(responseCode = "404", description = "배송 계획을 찾을 수 없음"),
    ])
    @GetMapping("/delivery-plans/{planId}")
    fun getDeliveryPlan(
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
    ): AdminDeliveryPlanDetailResponse = adminDeliveryPlanService.getDeliveryPlan(planId)

    @Operation(summary = "배송 정보 통계 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송 정보 통계 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
    ])
    @GetMapping("/delivery-plans/statistics")
    fun getDeliveryStatistics(): AdminDeliveryStatisticsResponse =
        adminDeliveryPlanService.getDeliveryStatistics()

    @Operation(
        summary = "배송 업무별 배송 기사 추천",
        description = """
            아직 배정되지 않은(OPEN) 또는 배정 직후(READY) 배송 업무에 적합한 기사를 점수순으로 추천합니다.
            거리, 진행 중 업무량, 위험 배송지 보유량, 위치 정보 신선도를 가중합해 0~100점으로 산출하며
            피처별 기여도와 근거 문구를 함께 반환합니다.
            추천은 배정이 아니라 참고용 랭킹이며, 실제 수령은 기사가 직접 수행합니다.
        """,
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송 기사 추천 성공"),
        ApiResponse(responseCode = "400", description = "이미 배송 중이거나 완료된 업무"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
        ApiResponse(responseCode = "404", description = "배송 계획을 찾을 수 없음"),
    ])
    @GetMapping("/delivery-plans/{planId}/driver-recommendations")
    fun recommendDrivers(
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
        @Parameter(description = "추천받을 기사 수 (1~10, 기본 3)", example = "3")
        @RequestParam(required = false) limit: Int?,
    ): DriverRecommendationResponse = driverRecommendationService.recommend(planId, limit)

    @Operation(
        summary = "미배정 배송 업무 등록",
        description = "기사를 지정하지 않고 배송 업무를 등록합니다. 등록된 업무는 OPEN 상태로 배송 기사들이 선착순으로 수령합니다.",
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "미배정 배송 업무 등록 성공"),
        ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
    ])
    @PostMapping("/delivery-plans")
    fun createOpen(
        @Valid @RequestBody request: CreateDeliveryPlanRequest,
    ): ResponseEntity<CreateDeliveryPlanResponse> {
        val planId = deliveryPlanCreationFacade.createOpen(request)
        return ResponseEntity.created(URI.create("/api/admin/delivery-plans/$planId"))
            .body(CreateDeliveryPlanResponse(planId))
    }

    @Operation(summary = "배송 계획 생성 및 기사 할당", description = "지정한 배송 기사에게 새로운 배송 계획을 생성해 할당합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "201", description = "배송 계획 생성 및 할당 성공"),
        ApiResponse(responseCode = "400", description = "기사 또는 요청 값이 올바르지 않음"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
    ])
    @PostMapping("/drivers/{driverId}/delivery-plans")
    fun create(
        @Parameter(description = "계획을 할당할 배송 기사 ID", example = "1") @PathVariable driverId: Long,
        @Valid @RequestBody request: CreateDeliveryPlanRequest,
    ): ResponseEntity<CreateDeliveryPlanResponse> {
        val planId = deliveryPlanCreationFacade.create(driverId, request)
        return ResponseEntity.created(URI.create("/api/admin/delivery-plans/$planId"))
            .body(CreateDeliveryPlanResponse(planId))
    }
}
