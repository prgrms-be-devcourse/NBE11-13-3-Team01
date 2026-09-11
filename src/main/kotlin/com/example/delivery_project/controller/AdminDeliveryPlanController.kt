package com.example.delivery_project.controller

import com.example.delivery_project.dto.request.CreateDeliveryPlanRequest
import com.example.delivery_project.dto.response.AdminDeliveryPlanDetailResponse
import com.example.delivery_project.dto.response.AdminDeliveryPlanSummaryResponse
import com.example.delivery_project.dto.response.CreateDeliveryPlanResponse
import com.example.delivery_project.service.AdminDeliveryPlanService
import com.example.delivery_project.service.DeliveryPlanCreationFacade
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
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "관리자 배송 계획", description = "전체 배송 계획 조회 및 배송 기사 할당 API")
class AdminDeliveryPlanController(
    private val adminDeliveryPlanService: AdminDeliveryPlanService,
    private val deliveryPlanCreationFacade: DeliveryPlanCreationFacade,
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
