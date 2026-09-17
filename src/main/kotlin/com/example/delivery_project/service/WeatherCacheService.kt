package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.weather.Weather
import com.example.delivery_project.domain.repository.GridCoordinate
import com.example.delivery_project.domain.repository.WeatherCacheRepository
import com.example.delivery_project.domain.repository.WeatherRepository
import com.example.delivery_project.dto.cache.WeatherRiskCache
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime

@Service
class WeatherCacheService(
    private val weatherCacheRepository: WeatherCacheRepository,
    private val weatherRepository: WeatherRepository,
    @param:Value("\${weather.cache.ttl:2h}") private val cacheTtl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Cache-Aside: Redis 우선 조회, 미스인 좌표만 모아 DB를 한 번에 조회한 뒤 Redis에 재캐싱한다.
    fun getWeatherValues(
        coordinates: Set<GridCoordinate>,
        earliestForecastAt: LocalDateTime,
        currentForecastAt: LocalDateTime,
    ): Map<GridCoordinate, Map<String, String>?> {
        if (coordinates.isEmpty()) return emptyMap()

        val result = mutableMapOf<GridCoordinate, WeatherRiskCache?>()
        val missCoordinates = mutableSetOf<GridCoordinate>()
        for (coordinate in coordinates) {
            val cached = findValidFromCache(coordinate, earliestForecastAt, currentForecastAt)
            if (cached != null) {
                result[coordinate] = cached
            } else {
                missCoordinates += coordinate
            }
        }

        if (missCoordinates.isNotEmpty()) {
            result += fetchAndCacheFromDb(missCoordinates, earliestForecastAt, currentForecastAt)
        }

        return result.mapValues { (_, cache) -> cache?.toValues() }
    }

    // Redis TTL이 남아 있어도 조회 시점의 시간 창이 이동했을 수 있으므로,
    // forecastAt이 현재 조회 범위 안에 있을 때만 HIT로 인정한다.
    private fun findValidFromCache(
        coordinate: GridCoordinate,
        earliestForecastAt: LocalDateTime,
        currentForecastAt: LocalDateTime,
    ): WeatherRiskCache? {
        val cached = findFromCache(coordinate) ?: return null
        if (!cached.forecastAt.isBefore(earliestForecastAt) && !cached.forecastAt.isAfter(currentForecastAt)) {
            return cached
        }
        log.info(
            "날씨 캐시가 조회 범위를 벗어나 MISS로 처리합니다. nx={}, ny={}, forecastAt={}",
            coordinate.nx, coordinate.ny, cached.forecastAt,
        )
        evictStaleCache(coordinate)
        return null
    }

    private fun findFromCache(coordinate: GridCoordinate): WeatherRiskCache? =
        runCatching { weatherCacheRepository.find(coordinate.nx, coordinate.ny) }
            .onFailure {
                log.warn("날씨 캐시 조회 실패. DB 조회로 대체합니다. nx={}, ny={}", coordinate.nx, coordinate.ny, it)
            }
            .getOrNull()

    private fun evictStaleCache(coordinate: GridCoordinate) {
        runCatching { weatherCacheRepository.delete(coordinate.nx, coordinate.ny) }
            .onFailure {
                log.warn("stale 날씨 캐시 삭제 실패. TTL 만료로 자연 정리됩니다. nx={}, ny={}", coordinate.nx, coordinate.ny, it)
            }
    }

    // 격자 좌표당 DB 조회 1회를 넘지 않도록, 캐시 미스가 발생한 좌표만 모아 한 번에 조회한다.
    private fun fetchAndCacheFromDb(
        coordinates: Set<GridCoordinate>,
        earliestForecastAt: LocalDateTime,
        currentForecastAt: LocalDateTime,
    ): Map<GridCoordinate, WeatherRiskCache?> {
        val weathers = weatherRepository.findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(
            coordinates.map { it.nx }.toSet(),
            coordinates.map { it.ny }.toSet(),
            earliestForecastAt.toLocalDate(),
            currentForecastAt.toLocalDate(),
            RISK_CATEGORIES,
        )
        val weathersByCoordinate = weathers
            .filter { GridCoordinate(it.nx, it.ny) in coordinates }
            .groupBy { GridCoordinate(it.nx, it.ny) }

        return coordinates.associateWith { coordinate ->
            val cache = selectLatestWeatherRiskCache(
                weathersByCoordinate[coordinate].orEmpty(),
                earliestForecastAt,
                currentForecastAt,
            )
            if (cache != null) {
                saveToCache(coordinate, cache)
            }
            cache
        }
    }

    private fun saveToCache(coordinate: GridCoordinate, cache: WeatherRiskCache) {
        runCatching { weatherCacheRepository.save(coordinate.nx, coordinate.ny, cache, cacheTtl) }
            .onFailure {
                log.warn("날씨 캐시 저장 실패. nx={}, ny={}", coordinate.nx, coordinate.ny, it)
            }
    }

    // 현재 예보 시각부터 직전 2시간 이내의 가장 최신인 완전한 데이터 세트를 선택한다.
    private fun selectLatestWeatherRiskCache(
        weathers: List<Weather>,
        earliestForecastAt: LocalDateTime,
        currentForecastAt: LocalDateTime,
    ): WeatherRiskCache? {
        val valuesByForecastAt = weathers.groupBy { LocalDateTime.of(it.fcstDate, it.fcstTime) }
            .mapValues { (_, forecasts) -> forecasts.associate { it.category to it.fcstValue } }
        return valuesByForecastAt.entries
            .filter { (at, values) ->
                !at.isBefore(earliestForecastAt) && !at.isAfter(currentForecastAt) &&
                    values.keys.containsAll(RISK_CATEGORIES)
            }
            .maxByOrNull { it.key }
            ?.let { (forecastAt, values) ->
                WeatherRiskCache(
                    forecastAt = forecastAt,
                    t1h = requireNotNull(values["T1H"]),
                    rn1 = requireNotNull(values["RN1"]),
                    pty = requireNotNull(values["PTY"]),
                )
            }
    }

    private companion object {
        val RISK_CATEGORIES = listOf("T1H", "RN1", "PTY")
    }
}
