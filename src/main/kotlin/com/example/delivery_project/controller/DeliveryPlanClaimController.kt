package com.example.delivery_project.controller

import com.example.delivery_project.dto.response.ClaimDeliveryPlanResponse
import com.example.delivery_project.dto.response.OpenDeliveryPlanResponse
import com.example.delivery_project.security.auth.CustomUserDetails
import com.example.delivery_project.service.DeliveryPlanClaimService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/delivery-plans")
@PreAuthorize("hasRole('DELIVERY_DRIVER')")
@Tag(name = "배송 업무 수령", description = "미배정 배송 업무 조회 및 선착순 수령·반납 API")
class DeliveryPlanClaimController(
    private val deliveryPlanClaimService: DeliveryPlanClaimService,
) {
    @Operation(
        summary = "미배정 배송 업무 목록 조회",
        description = """
            관리자가 등록했지만 아직 아무도 수령하지 않은 OPEN 상태의 배송 업무를 조회합니다.
            추천 상위 기사에게 우선 수령 권한이 열린 업무도 함께 내려오며,
            본인의 우선권 순위(priorityRank), 전체 공개 시각(publicAt),
            지금 수령 가능한지 여부(claimableNow)가 포함됩니다.
        """,
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "미배정 업무 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "배송 기사 권한 필요"),
    ])
    @GetMapping("/open")
    fun getOpenDeliveryPlans(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): List<OpenDeliveryPlanResponse> =
        deliveryPlanClaimService.getOpenPlans(requireNotNull(userDetails.user.id))

    @Operation(
        summary = "배송 업무 수령",
        description = """
            OPEN 상태의 배송 업무를 선착순으로 수령합니다.
            여러 기사가 동시에 같은 업무를 요청하면 한 명만 성공하고 나머지는 409 를 받습니다.
            이미 본인이 수령한 업무를 다시 요청하면 alreadyOwned=true 로 200 을 반환합니다. (멱등)
        """,
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송 업무 수령 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "배송 기사 권한 필요"),
        ApiResponse(responseCode = "404", description = "배송 업무 또는 기사를 찾을 수 없음"),
        ApiResponse(
            responseCode = "409",
            description = "이미 다른 기사가 수령했거나, 동시 보유 한도를 초과했거나, 아직 추천 기사 우선 수령 시간임",
        ),
    ])
    @PostMapping("/{planId}/claim")
    fun claim(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
    ): ClaimDeliveryPlanResponse =
        deliveryPlanClaimService.claim(planId, requireNotNull(userDetails.user.id))

    @Operation(
        summary = "배송 업무 반납",
        description = "배송을 시작하기 전(READY)에만 수령한 업무를 다시 OPEN 상태로 반납할 수 있습니다.",
    )
    @ApiResponses(value = [
        ApiResponse(responseCode = "204", description = "배송 업무 반납 성공"),
        ApiResponse(responseCode = "400", description = "이미 배송을 시작해 반납할 수 없음"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "배송 기사 권한 필요"),
        ApiResponse(responseCode = "404", description = "본인이 수령한 배송 업무가 아님"),
    ])
    @DeleteMapping("/{planId}/claim")
    fun release(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Parameter(description = "배송 계획 ID", example = "1") @PathVariable planId: Long,
    ): ResponseEntity<Void> {
        deliveryPlanClaimService.release(planId, requireNotNull(userDetails.user.id))
        return ResponseEntity.noContent().build()
    }
}
