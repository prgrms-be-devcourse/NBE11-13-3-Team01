package com.example.delivery_project.domain.entity.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "driver_location",
    uniqueConstraints = [UniqueConstraint(name = "uk_driver_location_driver", columnNames = ["driver_id"])],
    indexes = [Index(name = "idx_driver_location_updated_at", columnList = "updated_at")],
)
class DriverLocation private constructor(
    driver: User,
    latitude: Double,
    longitude: Double,
    updatedAt: LocalDateTime,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:OneToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "driver_id", nullable = false)
    var driver: User = driver
        protected set

    @field:Column(nullable = false)
    var latitude: Double = latitude
        protected set

    @field:Column(nullable = false)
    var longitude: Double = longitude
        protected set

    @field:Column(nullable = false)
    var updatedAt: LocalDateTime = updatedAt
        protected set

    fun update(latitude: Double, longitude: Double, updatedAt: LocalDateTime) {
        this.latitude = latitude
        this.longitude = longitude
        this.updatedAt = updatedAt
    }

    companion object {
        fun create(
            driver: User,
            latitude: Double,
            longitude: Double,
            updatedAt: LocalDateTime,
        ): DriverLocation = DriverLocation(driver, latitude, longitude, updatedAt)
    }
}
