package com.example.delivery_project.domain.entity.weather

import com.example.delivery_project.exception.RiskException
import com.example.delivery_project.exception.global.BusinessException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.assertFailsWith

class WeatherTest {

    @Test
    fun `날씨를 생성하고 예보값을 갱신한다`() {
        val weather = Weather.of(
            60,
            127,
            LocalDate.of(2026, 8, 18),
            LocalTime.of(11, 0),
            LocalDate.of(2026, 8, 18),
            LocalTime.of(10, 30),
            "T1H",
            "32",
        )
        val fetchedAt = LocalDateTime.now().plusMinutes(1)

        weather.updateFcstValue("33", fetchedAt)

        assertThat(weather.fcstValue).isEqualTo("33")
        assertThat(weather.fetchedAt).isEqualTo(fetchedAt)
    }

    @Test
    fun `좌표와 카테고리는 필수다`() {
        assertRiskArgumentError {
            Weather.of(null, 127, LocalDate.now(), LocalTime.now(), LocalDate.now(), LocalTime.now(), "T1H", "30")
        }
        assertRiskArgumentError {
            Weather.of(60, 127, LocalDate.now(), LocalTime.now(), LocalDate.now(), LocalTime.now(), " ", "30")
        }
    }

    private fun assertRiskArgumentError(action: () -> Unit) {
        val exception = assertFailsWith<BusinessException>(block = action)
        assertThat(exception.errorCode).isEqualTo(RiskException.RISK_ARGUMENT_NOT_IMPLEMENTED)
    }
}
