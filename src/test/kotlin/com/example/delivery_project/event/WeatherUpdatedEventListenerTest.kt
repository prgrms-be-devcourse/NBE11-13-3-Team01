package com.example.delivery_project.event

import com.example.delivery_project.domain.repository.WeatherCacheRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class WeatherUpdatedEventListenerTest {
    private val weatherCacheRepository = mock<WeatherCacheRepository>()
    private val listener = WeatherUpdatedEventListener(weatherCacheRepository)

    @Test
    fun Weather_갱신_커밋_후_해당_좌표의_캐시를_무효화한다() {
        listener.evictCacheAfterWeatherUpdated(WeatherUpdatedEvent(60, 127))
        verify(weatherCacheRepository).delete(60, 127)
    }

    @Test
    fun 캐시_무효화_실패는_예외로_전파하지_않는다() {
        doThrow(RuntimeException("Redis 장애")).whenever(weatherCacheRepository).delete(60, 127)
        listener.evictCacheAfterWeatherUpdated(WeatherUpdatedEvent(60, 127))
    }
}
