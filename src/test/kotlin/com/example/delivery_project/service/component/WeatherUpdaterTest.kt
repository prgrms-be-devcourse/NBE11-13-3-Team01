package com.example.delivery_project.service.component

import com.example.delivery_project.domain.entity.weather.Weather
import com.example.delivery_project.domain.repository.WeatherRepository
import com.example.delivery_project.dto.request.WeatherRequest
import com.example.delivery_project.dto.response.WeatherResponse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeatherUpdaterTest {
    private val provider = mock<WeatherProvider>()
    private val repository = mock<WeatherRepository>()
    private val updater = WeatherUpdater(provider, repository)

    @Test
    fun 정상_응답의_기존_날씨를_UPDATE한다() {
        whenever(provider.getWeather(request())).thenReturn(successResponse())
        whenever(repository.updateFcstValue(any(), any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(1)
        assertTrue(updater.update(request()))
        verify(repository).updateFcstValue(any(), any(), any(), any(), any(), any(), any(), any(), any())
        verify(repository, never()).save(any<Weather>())
    }

    @Test
    fun 기존_날씨가_없으면_INSERT한다() {
        whenever(provider.getWeather(request())).thenReturn(successResponse())
        assertTrue(updater.update(request()))
        val captor = argumentCaptor<Weather>()
        verify(repository).save(captor.capture())
        with(captor.firstValue) {
            assertEquals(60, nx)
            assertEquals(127, ny)
            assertEquals(LocalDate.of(2026, 8, 18), baseDate)
            assertEquals(LocalTime.of(10, 30), baseTime)
            assertEquals(LocalTime.of(11, 0), fcstTime)
            assertEquals("T1H", category)
            assertEquals("33", fcstValue)
        }
    }

    @Test
    fun 기상_API가_실패_코드를_반환하면_저장하지_않는다() {
        whenever(provider.getWeather(request())).thenReturn(
            WeatherResponse(WeatherResponse.Header("03", "NO_DATA"), null)
        )
        assertFalse(updater.update(request()))
        verifyNoInteractions(repository)
    }

    @Test
    fun 아직_공개되지_않은_발표시각은_선택하지_않는다() {
        val seoul = ZoneId.of("Asia/Seoul")
        val lowerBound = LocalDateTime.now(seoul).minusMinutes(76)
        val result = updater.resolveLatestBaseDateTime()
        val resolved = LocalDateTime.of(
            LocalDate.parse(result.baseDate, DateTimeFormatter.BASIC_ISO_DATE),
            LocalTime.parse(result.baseTime, DateTimeFormatter.ofPattern("HHmm")),
        )
        val upperBound = LocalDateTime.now(seoul).minusMinutes(14)
        assertEquals(30, resolved.minute)
        assertTrue(resolved >= lowerBound && resolved <= upperBound)
    }

    private fun request() = WeatherRequest(baseDate = "20260818", baseTime = "1030", nx = 60, ny = 127)

    private fun successResponse() = WeatherResponse(
        WeatherResponse.Header("00", "NORMAL_SERVICE"),
        WeatherResponse.Body(
            "JSON",
            WeatherResponse.Items(listOf(WeatherResponse.Item("20260818", "1030", "T1H", "20260818", "1100", "33", 60, 127))),
            1, 10, 1,
        ),
    )
}
