package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.weather.Weather
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

interface WeatherRepository : JpaRepository<Weather, Long> {
    fun findByNxAndNyAndFcstDateAndFcstTimeAndCategoryIn(
        nx: Int,
        ny: Int,
        fcstDate: LocalDate,
        fcstTime: LocalTime,
        categories: List<String>,
    ): List<Weather>

    fun findByNxAndNyAndFcstDateBetweenAndCategoryIn(
        nx: Int,
        ny: Int,
        startDate: LocalDate,
        endDate: LocalDate,
        categories: List<String>,
    ): List<Weather>

    fun findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(
        nxValues: Collection<Int>,
        nyValues: Collection<Int>,
        startDate: LocalDate,
        endDate: LocalDate,
        categories: Collection<String>,
    ): List<Weather>

    @Modifying
    @Query(
        """
        UPDATE Weather w
        SET w.fcstValue = :fcstValue, w.baseDate = :baseDate, w.baseTime = :baseTime, w.fetchedAt = :fetchedAt
        WHERE w.nx = :nx AND w.ny = :ny
          AND w.fcstDate = :fcstDate AND w.fcstTime = :fcstTime
          AND w.category = :category
        """,
    )
    fun updateFcstValue(
        @Param("nx") nx: Int,
        @Param("ny") ny: Int,
        @Param("fcstDate") fcstDate: LocalDate,
        @Param("fcstTime") fcstTime: LocalTime,
        @Param("baseDate") baseDate: LocalDate,
        @Param("baseTime") baseTime: LocalTime,
        @Param("category") category: String,
        @Param("fcstValue") fcstValue: String,
        @Param("fetchedAt") fetchedAt: LocalDateTime,
    ): Int
}
