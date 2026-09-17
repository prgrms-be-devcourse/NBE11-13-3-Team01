package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.domain.entity.delivery.RiskAssessment
import com.example.delivery_project.domain.repository.GridCoordinate
import com.example.delivery_project.domain.repository.RiskAssessmentRepository
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

@ExtendWith(MockitoExtension::class)
class RiskAssessmentServiceTest {
    @Mock lateinit var weatherCacheService: WeatherCacheService
    @Mock lateinit var riskFactorCalculator: RiskFactorCalculator
    @Mock lateinit var riskAssessmentRepository: RiskAssessmentRepository
    @Mock lateinit var demoRiskScenarioPolicy: DemoRiskScenarioPolicy
    @Mock lateinit var stop: DeliveryStop
    @InjectMocks lateinit var service: RiskAssessmentService

    @Test
    fun 현재_데이터가_불완전하면_2시간_이내의_완전한_데이터를_사용한다() {
        val assessment = RiskAssessment.of(stop, LocalDateTime.now())
        givenStopAndAssessment(assessment)
        givenWeatherValues(mapOf("T1H" to "20", "RN1" to "0", "PTY" to "0"))
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
        givenWeatherValues(null)
        service.updateAssessments(listOf(stop))
        assertThat(assessment.level).isEqualTo(RiskLevel.UNKNOWN)
        assertThat(assessment.score).isEqualTo(-1)
        assertThat(assessment.riskFactors).isEmpty()
    }

    @Test
    fun 현재_날씨가_폭우와_폭염이면_두_위험요인을_반영한다() {
        val assessment = RiskAssessment.of(stop, LocalDateTime.now())
        givenStopAndAssessment(assessment)
        givenWeatherValues(mapOf("T1H" to "35", "RN1" to "30mm 이상", "PTY" to "1"))
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
        verifyNoInteractions(weatherCacheService, riskFactorCalculator)
    }

    @Test
    fun 여러_배송지의_날씨를_한번에_조회한다() {
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
        givenWeatherValues(null)
        service.updateAssessments(stops)
        verify(riskAssessmentRepository, times(1)).findAllWithFactorsByDeliveryStopIdIn(stopIds)
        // 좌표가 여러 개여도 WeatherCacheService 호출은 한 번으로 묶여야 한다(N+1 방지).
        verify(weatherCacheService, times(1)).getWeatherValues(any(), any(), any())
    }

    private fun givenStopAndAssessment(assessment: RiskAssessment) {
        whenever(stop.id).thenReturn(1L)
        whenever(stop.latitude).thenReturn(37.5665)
        whenever(stop.longitude).thenReturn(126.9780)
        whenever(riskAssessmentRepository.findAllWithFactorsByDeliveryStopIdIn(listOf(1L))).thenReturn(listOf(assessment))
    }

    // WeatherCacheService는 이미 선택이 끝난 날씨 값을 돌려주는 계층이므로,
    // "최근 2시간 내 완전한 세트 선택" 결과 자체를 그대로 stub 한다.
    private fun givenWeatherValues(values: Map<String, String>?) {
        whenever(weatherCacheService.getWeatherValues(any(), any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            val coordinates = invocation.getArgument<Set<GridCoordinate>>(0)
            coordinates.associateWith { values }
        }
    }
}
