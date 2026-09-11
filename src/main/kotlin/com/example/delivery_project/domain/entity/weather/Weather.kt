package com.example.delivery_project.domain.entity.weather

import com.example.delivery_project.exception.RiskException
import com.example.delivery_project.exception.global.BusinessException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Entity
@Table(
    name = "weather",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_weather_slot",
            columnNames = ["nx", "ny", "fcstDate", "fcstTime", "category"],
        ),
    ],
)
class Weather private constructor(
    nx: Int,
    ny: Int,
    fcstDate: LocalDate,
    fcstTime: LocalTime,
    baseDate: LocalDate,
    baseTime: LocalTime,
    category: String,
    fcstValue: String,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(nullable = false)
    var nx: Int = nx
        protected set

    @field:Column(nullable = false)
    var ny: Int = ny
        protected set

    @field:Column(nullable = false)
    var fcstDate: LocalDate = fcstDate
        protected set

    @field:Column(nullable = false)
    var fcstTime: LocalTime = fcstTime
        protected set

    @field:Column(nullable = false)
    var baseDate: LocalDate = baseDate
        protected set

    @field:Column(nullable = false)
    var baseTime: LocalTime = baseTime
        protected set

    @field:Column(nullable = false, length = 10)
    var category: String = category
        protected set

    @field:Column(nullable = false, length = 50)
    var fcstValue: String = fcstValue
        protected set

    @field:Column(nullable = false)
    var fetchedAt: LocalDateTime = LocalDateTime.now()
        protected set

    fun updateFcstValue(fcstValue: String, fetchedAt: LocalDateTime) {
        this.fcstValue = fcstValue
        this.fetchedAt = fetchedAt
    }

    companion object {
        fun of(
            nx: Int?,
            ny: Int?,
            fcstDate: LocalDate,
            fcstTime: LocalTime,
            baseDate: LocalDate,
            baseTime: LocalTime,
            category: String?,
            fcstValue: String,
        ): Weather {
            if (nx == null || ny == null) {
                throw BusinessException(
                    RiskException.RISK_ARGUMENT_NOT_IMPLEMENTED,
                    "격자 좌표(nx, ny)는 필수입니다.",
                )
            }
            if (category.isNullOrBlank()) {
                throw BusinessException(
                    RiskException.RISK_ARGUMENT_NOT_IMPLEMENTED,
                    "카테고리는 필수입니다.",
                )
            }
            return Weather(nx, ny, fcstDate, fcstTime, baseDate, baseTime, category, fcstValue)
        }
    }
}
