package com.example.delivery_project.controller

import com.example.delivery_project.dto.response.DriverSummaryResponse
import com.example.delivery_project.service.DriverQueryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AdminDriverControllerTest {
    private val driverQueryService = mock<DriverQueryService>()
    private val controller = AdminDriverController(driverQueryService)

    @Test
    fun `관리자는 배송기사 목록을 조회한다`() {
        val driver = DriverSummaryResponse(7L, "driver", "배송기사")
        whenever(driverQueryService.getDrivers()).thenReturn(listOf(driver))

        assertThat(controller.getDrivers()).containsExactly(driver)
        verify(driverQueryService).getDrivers()
    }
}
