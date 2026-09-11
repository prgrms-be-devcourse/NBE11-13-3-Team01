package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.user.User

data class DriverSummaryResponse(
    val driverId: Long?,
    val loginId: String,
    val name: String,
) {
    companion object {
        fun from(driver: User) = DriverSummaryResponse(driver.id, driver.loginId, driver.name)
    }
}
