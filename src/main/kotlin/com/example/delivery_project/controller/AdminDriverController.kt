package com.example.delivery_project.controller

import com.example.delivery_project.dto.response.DriverSummaryResponse
import com.example.delivery_project.dto.response.DriverLocationResponse
import com.example.delivery_project.service.DriverLocationService
import com.example.delivery_project.service.DriverQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/drivers")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "관리자 배송 기사", description = "배송 기사 조회 및 최신 위치 모니터링 API")
class AdminDriverController(
    private val driverQueryService: DriverQueryService,
    private val driverLocationService: DriverLocationService,
) {
    @Operation(summary = "배송 기사 목록 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송 기사 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
    ])
    @GetMapping
    fun getDrivers(): List<DriverSummaryResponse> = driverQueryService.getDrivers()

    @Operation(summary = "전체 배송 기사 최신 위치 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송 기사 위치 목록 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
    ])
    @GetMapping("/locations")
    fun getDriverLocations(): List<DriverLocationResponse> = driverLocationService.getAllLocations()

    @Operation(summary = "배송 기사 최신 위치 조회")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "배송 기사 위치 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
        ApiResponse(responseCode = "403", description = "관리자 권한 필요"),
        ApiResponse(responseCode = "404", description = "등록된 위치 없음"),
    ])
    @GetMapping("/{driverId}/location")
    fun getDriverLocation(@PathVariable driverId: Long): DriverLocationResponse =
        driverLocationService.getLocation(driverId)
}
