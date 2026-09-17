package com.example.delivery_project.service.component

import com.example.delivery_project.domain.entity.weather.Weather
import com.example.delivery_project.domain.repository.WeatherRepository
import com.example.delivery_project.dto.request.WeatherRequest
import com.example.delivery_project.dto.response.WeatherResponse
import com.example.delivery_project.event.WeatherUpdatedEvent
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
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
    private val eventPublisher: ApplicationEventPublisher,
) {

    data class BaseDateTime(val baseDate: String, val baseTime: String)
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
        // item(T1H/RN1/PTY 등)별로 이벤트를 발행하지 않고, 이 요청(nx/ny)의 DB 갱신이
        // 모두 끝난 뒤 한 번만 발행한다. 실제 캐시 무효화는 이 트랜잭션이 커밋된 이후
        // WeatherUpdatedEventListener 가 수행하므로, 트랜잭션이 롤백되면 무효화도 일어나지 않는다.
        eventPublisher.publishEvent(WeatherUpdatedEvent(request.nx, request.ny))
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
