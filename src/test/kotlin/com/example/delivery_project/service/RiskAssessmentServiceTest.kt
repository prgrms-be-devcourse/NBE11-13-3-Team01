package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.domain.entity.delivery.RiskAssessment
import com.example.delivery_project.domain.entity.weather.Weather
import com.example.delivery_project.domain.repository.RiskAssessmentRepository
import com.example.delivery_project.domain.repository.WeatherRepository
import com.example.delivery_project.enums.RiskFactorType
import com.example.delivery_project.enums.RiskLevel
import com.example.delivery_project.exception.RiskException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.DemoRiskScenarioPolicy
import com.example.delivery_project.service.component.RiskFactorCalculator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
class RiskAssessmentServiceTest {
    @Mock lateinit var weatherRepository: WeatherRepository
    @Mock lateinit var riskFactorCalculator: RiskFactorCalculator
    @Mock lateinit var riskAssessmentRepository: RiskAssessmentRepository
    @Mock lateinit var demoRiskScenarioPolicy: DemoRiskScenarioPolicy
    @Mock lateinit var stop: DeliveryStop
    @InjectMocks lateinit var service: RiskAssessmentService

    @Test
    fun 현재_데이터가_불완전하면_2시간_이내의_완전한_데이터를_사용한다() {
        val currentForecastAt = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val fallbackForecastAt = currentForecastAt.minusHours(1)
        val assessment = RiskAssessment.of(stop, LocalDateTime.now())
        givenStopAndAssessment(assessment)
        val weathers = listOf(
            weather(currentForecastAt, "T1H", "21"),
            weather(fallbackForecastAt, "T1H", "20"),
            weather(fallbackForecastAt, "RN1", "0"),
            weather(fallbackForecastAt, "PTY", "0"),
        )
        whenever(weatherRepository.findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any()))
            .thenReturn(weathers)
        whenever(riskFactorCalculator.isHeavyRain("0", "0")).thenReturn(false)
        whenever(riskFactorCalculator.isHeatWave("20")).thenReturn(false)
        service.updateAssessments(listOf(stop))
        assertThat(assessment.level).isEqualTo(RiskLevel.SAFE)
        assertThat(assessment.score).isZero()
    }

    @Test
    fun 직전_2시간_이내의_완전한_날씨가_없으면_UNKNOWN으로_변경한다() {
        val assessment = RiskAssessment.of(stop, LocalDateTime.now())
        assessment.replaceFactors(listOf(RiskFactorType.HEAVY_RAIN), LocalDateTime.now())
        givenStopAndAssessment(assessment)
        whenever(weatherRepository.findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any()))
            .thenReturn(emptyList())
        service.updateAssessments(listOf(stop))
        assertThat(assessment.level).isEqualTo(RiskLevel.UNKNOWN)
        assertThat(assessment.score).isEqualTo(-1)
        assertThat(assessment.riskFactors).isEmpty()
    }

    @Test
    fun 현재_날씨가_폭우와_폭염이면_두_위험요인을_반영한다() {
        val forecastAt = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS)
        val assessment = RiskAssessment.of(stop, LocalDateTime.now())
        givenStopAndAssessment(assessment)
        val weathers = listOf(
            weather(forecastAt, "T1H", "35"), weather(forecastAt, "RN1", "30mm 이상"), weather(forecastAt, "PTY", "1"),
        )
        whenever(weatherRepository.findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any()))
            .thenReturn(weathers)
        whenever(riskFactorCalculator.isHeavyRain("30mm 이상", "1")).thenReturn(true)
        whenever(riskFactorCalculator.isHeatWave("35")).thenReturn(true)
        service.updateAssessments(listOf(stop))
        assertThat(assessment.riskFactors.map { it.type })
            .containsExactly(RiskFactorType.HEAVY_RAIN, RiskFactorType.HEAT_WAVE)
        assertThat(assessment.score).isEqualTo(50)
        assertThat(assessment.level).isEqualTo(RiskLevel.CAUTION)
    }

    @Test
    fun 배송지의_위험도_평가가_없으면_예외가_발생한다() {
        whenever(stop.id).thenReturn(999L)
        whenever(riskAssessmentRepository.findAllWithFactorsByDeliveryStopIdIn(listOf(999L))).thenReturn(emptyList())
        assertThat(assertThrows<BusinessException> { service.updateAssessments(listOf(stop)) }.errorCode)
            .isEqualTo(RiskException.RISK_NOT_FOUND)
        verifyNoInteractions(weatherRepository, riskFactorCalculator)
    }

    @Test
    fun 여러_배송지의_위험도와_날씨를_각각_한번에_조회한다() {
        val stops = (1..10).map { index ->
            mock<DeliveryStop>().also {
                whenever(it.id).thenReturn(index.toLong())
                whenever(it.latitude).thenReturn(37.5665)
                whenever(it.longitude).thenReturn(126.9780)
            }
        }
        val stopIds = stops.map { requireNotNull(it.id) }
        val assessments = stops.map { RiskAssessment.of(it, LocalDateTime.now()) }
        whenever(riskAssessmentRepository.findAllWithFactorsByDeliveryStopIdIn(stopIds)).thenReturn(assessments)
        whenever(weatherRepository.findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any()))
            .thenReturn(emptyList())
        service.updateAssessments(stops)
        verify(riskAssessmentRepository, times(1)).findAllWithFactorsByDeliveryStopIdIn(stopIds)
        verify(weatherRepository, times(1))
            .findByNxInAndNyInAndFcstDateBetweenAndCategoryIn(any(), any(), any(), any(), any())
    }

    private fun givenStopAndAssessment(assessment: RiskAssessment) {
        whenever(stop.id).thenReturn(1L)
        whenever(stop.latitude).thenReturn(37.5665)
        whenever(stop.longitude).thenReturn(126.9780)
        whenever(riskAssessmentRepository.findAllWithFactorsByDeliveryStopIdIn(listOf(1L))).thenReturn(listOf(assessment))
    }

    private fun weather(forecastAt: LocalDateTime, category: String, value: String): Weather =
        mock<Weather>().also {
            whenever(it.nx).thenReturn(60)
            whenever(it.ny).thenReturn(127)
            whenever(it.fcstDate).thenReturn(forecastAt.toLocalDate())
            whenever(it.fcstTime).thenReturn(forecastAt.toLocalTime())
            whenever(it.category).thenReturn(category)
            whenever(it.fcstValue).thenReturn(value)
        }
}
