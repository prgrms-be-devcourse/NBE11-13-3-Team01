package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.response.DriverSummaryResponse
import com.example.delivery_project.enums.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class DriverQueryServiceTest {
    @Mock lateinit var userRepository: UserRepository
    @InjectMocks lateinit var service: DriverQueryService

    @Test
    fun 배송기사_역할의_사용자만_요약해_반환한다() {
        val driver = User.of(7L, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)
        whenever(userRepository.findAllByRoleOrderByNameAsc(Role.ROLE_DELIVERY_DRIVER)).thenReturn(listOf(driver))
        assertThat(service.getDrivers()).containsExactly(DriverSummaryResponse(7L, "driver", "배송기사"))
        verify(userRepository).findAllByRoleOrderByNameAsc(Role.ROLE_DELIVERY_DRIVER)
    }
}
