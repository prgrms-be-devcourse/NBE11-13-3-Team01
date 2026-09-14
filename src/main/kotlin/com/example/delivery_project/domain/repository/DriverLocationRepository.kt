package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.user.DriverLocation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DriverLocationRepository : JpaRepository<DriverLocation, Long> {
    @Query("select l from DriverLocation l join fetch l.driver where l.driver.id = :driverId")
    fun findByDriverId(driverId: Long): DriverLocation?

    @Query("select l from DriverLocation l join fetch l.driver order by l.updatedAt desc")
    fun findAllWithDriverOrderByUpdatedAtDesc(): List<DriverLocation>
}
