package com.example.delivery_project.service.component

import com.example.delivery_project.domain.entity.weather.Weather
import com.example.delivery_project.domain.repository.WeatherRepository
import com.example.delivery_project.dto.request.WeatherRequest
import com.example.delivery_project.dto.response.WeatherResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Component
class WeatherUpdater(
    private val weatherProvider: WeatherProvider,
    private val weatherRepository: WeatherRepository,
) {

    data class BaseDateTime(val baseDate: String, val baseTime: String)

    // 초단기예보 발표시각(매시 30분) 기준으로 조회 가능한 가장 최근 발표시각을 계산한다.
    fun resolveLatestBaseDateTime(): BaseDateTime {
        val availableReference = LocalDateTime.now(SEOUL).minusMinutes(15)
        var baseDateTime = availableReference.withMinute(30).withSecond(0).withNano(0)

        if (baseDateTime.isAfter(availableReference)) {
            baseDateTime = baseDateTime.minusHours(1)
        }
        return BaseDateTime(
            baseDateTime.format(DATE_FORMATTER),
            baseDateTime.format(TIME_FORMATTER),
        )
    }

    fun update(request: WeatherRequest): Boolean {
        val response = requireNotNull(weatherProvider.getWeather(request)) { "기상 API 응답이 없습니다." }

        val header = requireNotNull(response.header)
        if (header.resultCode != "00") {
            log.info(
                "weather api result: code={}, msg={}",
                header.resultCode,
                header.resultMsg,
            )
            return false
        }

        val fetchedAt = LocalDateTime.now()
        for (item in requireNotNull(requireNotNull(response.body).items).item.orEmpty()) {
            upsert(item, fetchedAt)
        }
        return true
    }

    private fun upsert(item: WeatherResponse.Item, fetchedAt: LocalDateTime) {
        val fcstDate = LocalDate.parse(item.fcstDate, DATE_FORMATTER)
        val fcstTime = LocalTime.parse(item.fcstTime, TIME_FORMATTER)
        val baseDate = LocalDate.parse(item.baseDate, DATE_FORMATTER)
        val baseTime = LocalTime.parse(item.baseTime, TIME_FORMATTER)

        val updated = weatherRepository.updateFcstValue(
            requireNotNull(item.nx), requireNotNull(item.ny), fcstDate, fcstTime,
            baseDate, baseTime, requireNotNull(item.category), requireNotNull(item.fcstValue), fetchedAt,
        )

        if (updated == 0) {
            weatherRepository.save(
                Weather.of(
                    item.nx, item.ny, fcstDate, fcstTime,
                    baseDate, baseTime, item.category, item.fcstValue,
                )
            )
        }
    }

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmm")
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val log = LoggerFactory.getLogger(WeatherUpdater::class.java)
    }
}
