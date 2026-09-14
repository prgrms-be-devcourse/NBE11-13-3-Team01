package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.user.DriverLocation
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DriverLocationRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.request.UpdateDriverLocationRequest
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class DriverLocationServiceTest {
    @Mock lateinit var driverLocationRepository: DriverLocationRepository
    @Mock lateinit var userRepository: UserRepository
    @InjectMocks lateinit var service: DriverLocationService

    private val driver = User.of(7L, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)

    @Test
    fun `처음 받은 기사 위치를 등록한다`() {
        whenever(driverLocationRepository.findByDriverId(7L)).thenReturn(null)
        whenever(userRepository.findUserById(7L)).thenReturn(driver)
        whenever(driverLocationRepository.save(any<DriverLocation>())).thenAnswer { it.arguments[0] as DriverLocation }

        val response = service.updateLocation(7L, UpdateDriverLocationRequest(37.5665, 126.9780))

        assertThat(response.driverId).isEqualTo(7L)
        assertThat(response.latitude).isEqualTo(37.5665)
        assertThat(response.longitude).isEqualTo(126.9780)
        verify(driverLocationRepository).save(any<DriverLocation>())
    }

    @Test
    fun `기존 위치가 있으면 같은 행의 좌표와 갱신시각을 변경한다`() {
        val previousUpdatedAt = LocalDateTime.now().minusMinutes(1)
        val location = DriverLocation.create(driver, 37.0, 126.0, previousUpdatedAt)
        whenever(driverLocationRepository.findByDriverId(7L)).thenReturn(location)
        whenever(driverLocationRepository.save(location)).thenReturn(location)

        val response = service.updateLocation(7L, UpdateDriverLocationRequest(37.5, 127.0))

        assertThat(response.latitude).isEqualTo(37.5)
        assertThat(response.longitude).isEqualTo(127.0)
        assertThat(response.updatedAt).isAfter(previousUpdatedAt)
    }

    @Test
    fun `위치가 없는 기사를 조회하면 예외가 발생한다`() {
        whenever(driverLocationRepository.findByDriverId(7L)).thenReturn(null)

        assertThat(assertThrows<BusinessException> { service.getLocation(7L) }.errorCode)
            .isEqualTo(DeliveryException.DRIVER_LOCATION_NOT_FOUND)
    }

    @Test
    fun `관리자는 최신순 위치 목록을 조회한다`() {
        val location = DriverLocation.create(driver, 37.5, 127.0, LocalDateTime.now())
        whenever(driverLocationRepository.findAllWithDriverOrderByUpdatedAtDesc()).thenReturn(listOf(location))

        val response = service.getAllLocations().single()
        assertThat(response.driverId).isEqualTo(7L)
        assertThat(response.driverName).isEqualTo("배송기사")
    }
}
