package com.example.delivery_project.service.component

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LocationConverterTest {
    @Test
    fun 서울시청_좌표를_기상청_격자로_변환한다() {
        val grid = LocationConverter.convertGridGps(LocationConverter.TO_GRID, 37.5665, 126.9780)
        assertEquals(60.0, grid.x)
        assertEquals(127.0, grid.y)
    }

    @Test
    fun 격자를_위경도로_역변환할_수_있다() {
        val gps = LocationConverter.convertGridGps(LocationConverter.TO_GPS, 60.0, 127.0)
        assertEquals(37.5799, gps.lat, 0.1)
        assertEquals(126.9893, gps.lng, 0.1)
    }
}
