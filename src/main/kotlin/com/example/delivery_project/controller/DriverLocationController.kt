package com.example.delivery_project.controller

import com.example.delivery_project.dto.request.UpdateDriverLocationRequest
import com.example.delivery_project.dto.response.DriverLocationResponse
import com.example.delivery_project.security.auth.CustomUserDetails
import com.example.delivery_project.service.DriverLocationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/drivers/me/location")
@PreAuthorize("hasRole('DELIVERY_DRIVER')")
@Tag(name = "배송 기사 위치", description = "로그인한 배송 기사의 현재 위치 갱신 및 조회 API")
class DriverLocationController(private val driverLocationService: DriverLocationService) {
    @Operation(summary = "내 현재 위치 갱신")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "위치 갱신 성공"),
        ApiResponse(responseCode = "400", description = "위도 또는 경도가 올바르지 않음"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "배송 기사 권한 필요"),
    ])
    @PutMapping
    fun updateLocation(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: UpdateDriverLocationRequest,
    ): DriverLocationResponse =
        driverLocationService.updateLocation(requireNotNull(userDetails.user.id), request)

    @Operation(summary = "내 현재 위치 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "위치 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "배송 기사 권한 필요"),
        ApiResponse(responseCode = "404", description = "등록된 위치 없음"),
    ])
    @GetMapping
    fun getLocation(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): DriverLocationResponse =
        driverLocationService.getLocation(requireNotNull(userDetails.user.id))
}
