package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.domain.repository.DeliveryStopRepository
import com.example.delivery_project.dto.request.WeatherRequest
import com.example.delivery_project.enums.DeliveryStopStatus
import com.example.delivery_project.service.component.WeatherUpdater
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*

@ExtendWith(MockitoExtension::class)
class DeliveryRiskRefreshServiceTest {
    @Mock lateinit var deliveryStopRepository: DeliveryStopRepository
    @Mock lateinit var weatherService: WeatherService
    @Mock lateinit var riskAssessmentService: RiskAssessmentService
    @Mock lateinit var firstStop: DeliveryStop
    @Mock lateinit var secondStop: DeliveryStop
    @InjectMocks lateinit var service: DeliveryRiskRefreshService

    @Test
    fun 같은_격자의_배송지는_날씨를_한_번만_갱신한다() {
        givenBaseDateTime()
        givenSameLocationStops()
        whenever(weatherService.save(any())).thenReturn(true)
        service.refreshStops(listOf(firstStop, secondStop))
        verify(weatherService, times(1)).save(any())
        val captor = argumentCaptor<List<DeliveryStop>>()
        verify(riskAssessmentService).updateAssessments(captor.capture())
        assertThat(captor.firstValue).containsExactlyInAnyOrder(firstStop, secondStop)
    }

    @Test
    fun 날씨_API가_실패해도_저장된_데이터로_위험도_갱신을_시도한다() {
        givenBaseDateTime()
        givenSameLocationStops()
        whenever(weatherService.save(any())).thenThrow(IllegalStateException("기상 API 실패"))
        service.refreshStops(listOf(firstStop, secondStop))
        verify(riskAssessmentService).updateAssessments(any())
    }

    @Test
    fun READY와_DELIVERING_배송지만_전체_갱신_대상으로_조회한다() {
        whenever(deliveryStopRepository.findAllWithRiskByStatusIn(any())).thenReturn(emptyList())
        service.refreshActiveStops()
        verify(deliveryStopRepository).findAllWithRiskByStatusIn(argThat {
            contains(DeliveryStopStatus.READY) && contains(DeliveryStopStatus.DELIVERING) &&
                !contains(DeliveryStopStatus.COMPLETED)
        })
        verifyNoInteractions(weatherService, riskAssessmentService)
    }

    @Test
    fun 계획_생성_후에는_해당_계획의_활성_배송지만_조회한다() {
        whenever(deliveryStopRepository.findAllWithRiskByDeliveryPlanIdAndStatusIn(eq(10L), any()))
            .thenReturn(emptyList())
        service.refreshPlan(10L)
        verify(deliveryStopRepository).findAllWithRiskByDeliveryPlanIdAndStatusIn(eq(10L), argThat {
            contains(DeliveryStopStatus.READY) && contains(DeliveryStopStatus.DELIVERING) &&
                !contains(DeliveryStopStatus.COMPLETED)
        })
        verifyNoInteractions(weatherService, riskAssessmentService)
    }

    @Test
    fun 서로_다른_격자는_날씨를_각각_갱신하고_위험도는_한번에_갱신한다() {
        givenBaseDateTime()
        whenever(firstStop.latitude).thenReturn(37.5665)
        whenever(firstStop.longitude).thenReturn(126.9780)
        whenever(secondStop.latitude).thenReturn(35.1796)
        whenever(secondStop.longitude).thenReturn(129.0756)
        whenever(weatherService.save(any())).thenReturn(true)
        service.refreshStops(listOf(firstStop, secondStop))
        verify(weatherService, times(2)).save(any<WeatherRequest>())
        verify(riskAssessmentService, times(1)).updateAssessments(any())
    }

    private fun givenBaseDateTime() {
        whenever(weatherService.resolveLatestBaseDateTime()).thenReturn(WeatherUpdater.BaseDateTime("20260818", "1030"))
    }

    private fun givenSameLocationStops() {
        whenever(firstStop.latitude).thenReturn(37.5665)
        whenever(firstStop.longitude).thenReturn(126.9780)
        whenever(secondStop.latitude).thenReturn(37.5665)
        whenever(secondStop.longitude).thenReturn(126.9780)
    }
}
