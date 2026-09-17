package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.weather.Weather
import com.example.delivery_project.domain.repository.GridCoordinate
import com.example.delivery_project.domain.repository.WeatherCacheRepository
import com.example.delivery_project.domain.repository.WeatherRepository
import com.example.delivery_project.dto.cache.WeatherRiskCache
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.time.Duration
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class WeatherCacheServiceTest {
    @Mock lateinit var weatherCacheRepository: WeatherCacheRepository
    @Mock lateinit var weatherRepository: WeatherRepository
    private lateinit var service: WeatherCacheService

    private val currentForecastAt = LocalDateTime.of(2026, 1, 1, 9, 0)
    private val earliestForecastAt = currentForecastAt.minusHours(2)

    @BeforeEach
    fun setUp() {
        service = WeatherCacheService(weatherCacheRepository, weatherRepository, CACHE_TTL)
    }

    @Test
    fun `Redis 캐시가 조회 범위 안에 있으면 HIT로 사용하고 DB를 조회하지 않는다`() {
        val coordinate = GridCoordinate(60, 127)
        whenever(weatherCacheRepository.find(60, 127)).thenReturn(
            WeatherRiskCache(forecastAt = currentForecastAt.minusHours(1), t1h = "20", rn1 = "0", pty = "0"),
        )

        val result = service.getWeatherValues(setOf(coordinate), earliestForecastAt, currentForecastAt)

        assertThat(result[coordinate]).isEqualTo(mapOf("T1H" to "20", "RN1" to "0", "PTY" to "0"))
        verifyNoInteractions(weatherRepository)
    }

    @Test
    fun `Cache MISS 좌표들은 DB batch 조회를 한 번만 수행하고 결과를 각각 Redis에 저장한다`() {
        val coordinates = (1..10).map { GridCoordinate(60 + it, 127) }.toSet()
        whenever(weatherCacheRepository.find(any(), any())).thenReturn(null)
        val forecastAt = currentForecastAt.minusHours(1)
        val weathers = coordinates.flatMap { c ->
            listOf(
                weather(c.nx, c.ny, forecastAt, "T1H", "20"),
                weather(c.nx, c.ny, forecastAt, "RN1", "0"),
                weather(c.nx, c.ny, forecastAt, "PTY", "0"),
            )
        }
        whenever(
            weatherRepository.findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any()),
        ).thenReturn(weathers)

        val result = service.getWeatherValues(coordinates, earliestForecastAt, currentForecastAt)

        coordinates.forEach { c ->
            assertThat(result[c]).isEqualTo(mapOf("T1H" to "20", "RN1" to "0", "PTY" to "0"))
            verify(weatherCacheRepository).save(eq(c.nx), eq(c.ny), any(), eq(CACHE_TTL))
        }
        verify(weatherRepository, times(1))
            .findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any())
    }

    @Test
    fun `Redis 캐시의 forecastAt이 조회 범위보다 이르면 MISS로 취급해 DB에서 다시 조회한다`() {
        val coordinate = GridCoordinate(60, 127)
        whenever(weatherCacheRepository.find(60, 127)).thenReturn(
            WeatherRiskCache(forecastAt = earliestForecastAt.minusHours(1), t1h = "10", rn1 = "0", pty = "0"),
        )
        val forecastAt = currentForecastAt.minusHours(1)
        val weathers = listOf(
            weather(60, 127, forecastAt, "T1H", "25"),
            weather(60, 127, forecastAt, "RN1", "0"),
            weather(60, 127, forecastAt, "PTY", "0"),
        )
        whenever(
            weatherRepository.findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any()),
        ).thenReturn(weathers)

        val result = service.getWeatherValues(setOf(coordinate), earliestForecastAt, currentForecastAt)

        assertThat(result[coordinate]).isEqualTo(mapOf("T1H" to "25", "RN1" to "0", "PTY" to "0"))
        verify(weatherRepository).findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any())
        // stale 캐시는 best-effort로 삭제한다. 삭제가 실패해도 위 DB fallback은 이미 성공했다.
        verify(weatherCacheRepository).delete(60, 127)
    }

    @Test
    fun `최신 데이터가 불완전하면 그 이전의 완전한 데이터를 사용한다`() {
        val coordinate = GridCoordinate(60, 127)
        whenever(weatherCacheRepository.find(60, 127)).thenReturn(null)
        val weathers = listOf(
            weather(60, 127, currentForecastAt, "T1H", "22"),
            weather(60, 127, currentForecastAt.minusHours(1), "T1H", "20"),
            weather(60, 127, currentForecastAt.minusHours(1), "RN1", "0"),
            weather(60, 127, currentForecastAt.minusHours(1), "PTY", "0"),
        )
        whenever(
            weatherRepository.findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any()),
        ).thenReturn(weathers)

        val result = service.getWeatherValues(setOf(coordinate), earliestForecastAt, currentForecastAt)

        assertThat(result[coordinate]).isEqualTo(mapOf("T1H" to "20", "RN1" to "0", "PTY" to "0"))
    }

    @Test
    fun `Redis 조회가 실패해도 DB로 대체해 위험도 계산용 값을 반환한다`() {
        val coordinate = GridCoordinate(60, 127)
        whenever(weatherCacheRepository.find(60, 127)).thenThrow(RuntimeException("Redis 장애"))
        val forecastAt = currentForecastAt.minusHours(1)
        val weathers = listOf(
            weather(60, 127, forecastAt, "T1H", "20"),
            weather(60, 127, forecastAt, "RN1", "0"),
            weather(60, 127, forecastAt, "PTY", "0"),
        )
        whenever(
            weatherRepository.findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any()),
        ).thenReturn(weathers)

        val result = service.getWeatherValues(setOf(coordinate), earliestForecastAt, currentForecastAt)

        assertThat(result[coordinate]).isEqualTo(mapOf("T1H" to "20", "RN1" to "0", "PTY" to "0"))
    }

    @Test
    fun `Redis 저장이 실패해도 DB에서 찾은 값은 정상적으로 반환한다`() {
        val coordinate = GridCoordinate(60, 127)
        whenever(weatherCacheRepository.find(60, 127)).thenReturn(null)
        val forecastAt = currentForecastAt.minusHours(1)
        val weathers = listOf(
            weather(60, 127, forecastAt, "T1H", "20"),
            weather(60, 127, forecastAt, "RN1", "0"),
            weather(60, 127, forecastAt, "PTY", "0"),
        )
        whenever(
            weatherRepository.findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any()),
        ).thenReturn(weathers)
        whenever(weatherCacheRepository.save(any(), any(), any(), any())).thenThrow(RuntimeException("Redis 장애"))

        val result = service.getWeatherValues(setOf(coordinate), earliestForecastAt, currentForecastAt)

        assertThat(result[coordinate]).isEqualTo(mapOf("T1H" to "20", "RN1" to "0", "PTY" to "0"))
    }

    private fun weather(nx: Int, ny: Int, forecastAt: LocalDateTime, category: String, value: String): Weather =
        mock<Weather>().also {
            whenever(it.nx).thenReturn(nx)
            whenever(it.ny).thenReturn(ny)
            whenever(it.fcstDate).thenReturn(forecastAt.toLocalDate())
            whenever(it.fcstTime).thenReturn(forecastAt.toLocalTime())
            whenever(it.category).thenReturn(category)
            whenever(it.fcstValue).thenReturn(value)
        }

    private companion object {
        val CACHE_TTL: Duration = Duration.ofHours(2)
    }
}
