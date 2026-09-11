package com.example.delivery_project.service.component

import com.example.delivery_project.spec.GeocodedLocation
import com.example.delivery_project.spec.Location
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LocationMapperTest {
    @Test
    fun 지오코딩_결과를_도메인_위치로_변환한다() {
        val result = LocationMapper().toLocation(GeocodedLocation("서울시청", 37.5663, 126.9779))
        assertEquals(Location("서울시청", 37.5663, 126.9779), result)
    }
}
