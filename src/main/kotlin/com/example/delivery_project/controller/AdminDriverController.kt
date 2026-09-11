package com.example.delivery_project.controller

import com.example.delivery_project.dto.response.DriverSummaryResponse
import com.example.delivery_project.service.DriverQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/drivers")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "관리자 배송 기사", description = "배송 계획 할당을 위한 배송 기사 조회 API")
class AdminDriverController(private val driverQueryService: DriverQueryService) {
    @Operation(summary = "배송 기사 목록 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송 기사 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
    ])
    @GetMapping
    fun getDrivers(): List<DriverSummaryResponse> = driverQueryService.getDrivers()
}
