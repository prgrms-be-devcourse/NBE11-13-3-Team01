package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.domain.entity.delivery.RiskAssessment
import com.example.delivery_project.domain.entity.weather.Weather
import com.example.delivery_project.domain.repository.GridCoordinate
import com.example.delivery_project.domain.repository.RiskAssessmentRepository
import com.example.delivery_project.domain.repository.WeatherRepository
import com.example.delivery_project.enums.RiskFactorType
import com.example.delivery_project.exception.RiskException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.DemoRiskScenarioPolicy
import com.example.delivery_project.service.component.LocationConverter
import com.example.delivery_project.service.component.RiskFactorCalculator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@Service
@Transactional(readOnly = true)
class RiskAssessmentService(
    private val weatherRepository: WeatherRepository,
    private val riskFactorCalculator: RiskFactorCalculator,
    private val riskAssessmentRepository: RiskAssessmentRepository,
    private val demoRiskScenarioPolicy: DemoRiskScenarioPolicy,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun updateAssessments(deliveryStops: List<DeliveryStop>) {
        if (deliveryStops.isEmpty()) return
        val assessmentsByStopId = riskAssessmentRepository
            .findAllWithFactorsByDeliveryStopIdIn(deliveryStops.map { requireNotNull(it.id) })
            .associateBy { requireNotNull(it.deliveryStop).id }
        val analyzedAt = LocalDateTime.now()
        val weatherTargets = buildList {
            for (stop in deliveryStops) {
                val assessment = assessmentsByStopId[stop.id]
                    ?: throw BusinessException(RiskException.RISK_NOT_FOUND)
                if (demoRiskScenarioPolicy.applyIfEnabled(requireNotNull(stop.id), assessment, analyzedAt)) {
                    log.info(
                        "데모 위험도 시나리오를 적용합니다. stopId={}, level={}, score={}",
                        stop.id, assessment.level, assessment.score,
                    )
                } else {
                    add(AssessmentTarget(stop, assessment))
                }
            }
        }
        if (weatherTargets.isEmpty()) return
        val weatherByCoordinate = fetchWeatherValues(weatherTargets, analyzedAt)
        for ((stop, assessment) in weatherTargets) {
            val weatherValues = weatherByCoordinate[toGrid(stop)]
            if (weatherValues == null) {
                assessment.markUnknown(analyzedAt)
                log.warn("사용 가능한 날씨 데이터가 없어 위험도를 UNKNOWN으로 변경합니다. stopId={}", stop.id)
            } else {
                assessment.replaceFactors(calculateRiskFactorTypes(weatherValues), analyzedAt)
            }
        }
    }

    private fun calculateRiskFactorTypes(weatherValues: Map<String, String>): List<RiskFactorType> = buildList {
        if (riskFactorCalculator.isHeavyRain(weatherValues["RN1"], weatherValues["PTY"])) {
            add(RiskFactorType.HEAVY_RAIN)
        }
        if (riskFactorCalculator.isHeatWave(weatherValues["T1H"])) {
            add(RiskFactorType.HEAT_WAVE)
        }
    }

    // 현재 예보 시각부터 직전 2시간 이내의 가장 최신인 완전한 데이터 세트를 조회한다.
    private fun fetchWeatherValues(
        targets: List<AssessmentTarget>,
        now: LocalDateTime,
    ): Map<GridCoordinate, Map<String, String>?> {
        val coordinates = targets.map { toGrid(it.stop) }.toSet()
        val currentForecastAt = now.truncatedTo(ChronoUnit.HOURS)
        val earliestForecastAt = currentForecastAt.minusHours(WEATHER_FALLBACK_HOURS)
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
        return coordinates.associateWith {
            selectLatestWeatherValues(weathersByCoordinate[it].orEmpty(), earliestForecastAt, currentForecastAt)
        }
    }

    private fun selectLatestWeatherValues(
        weathers: List<Weather>,
        earliestForecastAt: LocalDateTime,
        currentForecastAt: LocalDateTime,
    ): Map<String, String>? {
        val valuesByForecastAt = weathers.groupBy { LocalDateTime.of(it.fcstDate, it.fcstTime) }
            .mapValues { (_, forecasts) -> forecasts.associate { it.category to it.fcstValue } }
        return valuesByForecastAt.entries
            .filter { (at, values) ->
                !at.isBefore(earliestForecastAt) && !at.isAfter(currentForecastAt) &&
                    values.keys.containsAll(RISK_CATEGORIES)
            }
            .maxByOrNull { it.key }
            ?.value
    }

    private fun toGrid(stop: DeliveryStop): GridCoordinate {
        val grid = LocationConverter.convertGridGps(LocationConverter.TO_GRID, stop.latitude, stop.longitude)
        return GridCoordinate(grid.x.toInt(), grid.y.toInt())
    }

    private data class AssessmentTarget(val stop: DeliveryStop, val assessment: RiskAssessment)

    private companion object {
        val RISK_CATEGORIES = listOf("T1H", "RN1", "PTY")
        const val WEATHER_FALLBACK_HOURS = 2L
    }
}
