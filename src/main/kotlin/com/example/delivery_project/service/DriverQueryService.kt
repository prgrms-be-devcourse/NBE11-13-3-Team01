package com.example.delivery_project.service

import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.response.DriverSummaryResponse
import com.example.delivery_project.enums.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class DriverQueryService(private val userRepository: UserRepository) {
    fun getDrivers(): List<DriverSummaryResponse> =
        userRepository.findAllByRoleOrderByNameAsc(Role.ROLE_DELIVERY_DRIVER).map(DriverSummaryResponse::from)
}
