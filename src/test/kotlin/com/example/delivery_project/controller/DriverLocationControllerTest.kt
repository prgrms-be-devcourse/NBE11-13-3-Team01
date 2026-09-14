package com.example.delivery_project.controller

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.dto.request.UpdateDriverLocationRequest
import com.example.delivery_project.dto.response.DriverLocationResponse
import com.example.delivery_project.enums.Role
import com.example.delivery_project.security.auth.CustomUserDetails
import com.example.delivery_project.service.DriverLocationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

class DriverLocationControllerTest {
    private val driverLocationService = mock<DriverLocationService>()
    private val controller = DriverLocationController(driverLocationService)
    private val userDetails = CustomUserDetails(
        User(7L, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER),
    )

    @Test
    fun `로그인 기사는 자신의 위치를 갱신하고 조회한다`() {
        val request = UpdateDriverLocationRequest(37.5665, 126.9780)
        val location = DriverLocationResponse(
            7L, "driver", "배송기사", 37.5665, 126.9780, LocalDateTime.now(),
        )
        whenever(driverLocationService.updateLocation(7L, request)).thenReturn(location)
        whenever(driverLocationService.getLocation(7L)).thenReturn(location)

        assertThat(controller.updateLocation(userDetails, request)).isEqualTo(location)
        assertThat(controller.getLocation(userDetails)).isEqualTo(location)
        verify(driverLocationService).updateLocation(7L, request)
        verify(driverLocationService).getLocation(7L)
    }
}
