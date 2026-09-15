package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.user.DriverLocation
import com.example.delivery_project.domain.repository.DriverLocationRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.request.UpdateDriverLocationRequest
import com.example.delivery_project.dto.response.DriverLocationResponse
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional(readOnly = true)
class DriverLocationService(
    private val driverLocationRepository: DriverLocationRepository,
    private val userRepository: UserRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun updateLocation(driverId: Long, request: UpdateDriverLocationRequest): DriverLocationResponse {
        val latitude = requireNotNull(request.latitude)
        val longitude = requireNotNull(request.longitude)
        val updatedAt = LocalDateTime.now()
        val location = driverLocationRepository.findByDriverId(driverId)?.apply {
            update(latitude, longitude, updatedAt)
        } ?: DriverLocation.create(
            driver = findDriver(driverId),
            latitude = latitude,
            longitude = longitude,
            updatedAt = updatedAt,
        )

        val savedLocation = driverLocationRepository.save(location)
        log.debug("[DRIVER][LOCATION] 위치 갱신 driverId: {}, updatedAt: {}", driverId, updatedAt)
        return DriverLocationResponse.from(savedLocation)
    }

    fun getLocation(driverId: Long): DriverLocationResponse =
        driverLocationRepository.findByDriverId(driverId)
            ?.let(DriverLocationResponse::from)
            ?: throw BusinessException(DeliveryException.DRIVER_LOCATION_NOT_FOUND)

    fun getAllLocations(): List<DriverLocationResponse> =
        driverLocationRepository.findAllWithDriverOrderByUpdatedAtDesc().map(DriverLocationResponse::from)

    private fun findDriver(driverId: Long) =
        userRepository.findUserById(driverId)
            ?.takeIf { it.role == Role.ROLE_DELIVERY_DRIVER }
            ?: throw BusinessException(DeliveryException.DELIVERY_DRIVER_NOT_FOUND)
}
