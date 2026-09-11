package com.example.delivery_project.service.component

import com.example.delivery_project.spec.GeocodedLocation

interface GeocodingClient {
    fun geocode(address: String?): GeocodedLocation
}
