package com.example.delivery_project.service.component

import com.example.delivery_project.spec.GeocodedLocation
import com.example.delivery_project.spec.Location
import org.springframework.stereotype.Component

@Component
class LocationMapper {
    fun toLocation(result: GeocodedLocation): Location = Location(
        result.address,
        result.latitude,
        result.longitude,
    )
}
