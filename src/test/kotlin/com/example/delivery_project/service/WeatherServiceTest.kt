package com.example.delivery_project.service

import com.example.delivery_project.dto.request.WeatherRequest
import com.example.delivery_project.service.component.WeatherUpdater
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class WeatherServiceTest {
    @Mock lateinit var weatherUpdater: WeatherUpdater
    @InjectMocks lateinit var weatherService: WeatherService

    @Test
    fun 날씨_저장_결과를_반환한다() {
        val request = WeatherRequest(baseDate = "20260818", baseTime = "1030", nx = 60, ny = 127)
        whenever(weatherUpdater.update(request)).thenReturn(true)
        assertThat(weatherService.save(request)).isTrue()
        verify(weatherUpdater).update(request)
    }

    @Test
    fun 최신_발표시각_계산을_위임한다() {
        val baseDateTime = WeatherUpdater.BaseDateTime("20260818", "1030")
        whenever(weatherUpdater.resolveLatestBaseDateTime()).thenReturn(baseDateTime)
        assertThat(weatherService.resolveLatestBaseDateTime()).isEqualTo(baseDateTime)
    }
}
