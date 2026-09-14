package com.example.delivery_project.controller

import com.example.delivery_project.dto.response.DriverSummaryResponse
import com.example.delivery_project.dto.response.DriverLocationResponse
import com.example.delivery_project.service.DriverLocationService
import com.example.delivery_project.service.DriverQueryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AdminDriverControllerTest {
    private val driverQueryService = mock<DriverQueryService>()
    private val driverLocationService = mock<DriverLocationService>()
    private val controller = AdminDriverController(driverQueryService, driverLocationService)

    @Test
    fun `관리자는 배송기사 목록을 조회한다`() {
        val driver = DriverSummaryResponse(7L, "driver", "배송기사")
        whenever(driverQueryService.getDrivers()).thenReturn(listOf(driver))

        assertThat(controller.getDrivers()).containsExactly(driver)
        verify(driverQueryService).getDrivers()
    }

    @Test
    fun `관리자는 전체 기사와 특정 기사의 최신 위치를 조회한다`() {
        val location = DriverLocationResponse(
            7L, "driver", "배송기사", 37.5665, 126.9780, java.time.LocalDateTime.now(),
        )
        whenever(driverLocationService.getAllLocations()).thenReturn(listOf(location))
        whenever(driverLocationService.getLocation(7L)).thenReturn(location)

        assertThat(controller.getDriverLocations()).containsExactly(location)
        assertThat(controller.getDriverLocation(7L)).isEqualTo(location)
    }
}
