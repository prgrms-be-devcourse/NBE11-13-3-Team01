package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.domain.repository.DeliveryStopRepository
import com.example.delivery_project.domain.repository.GridCoordinate
import com.example.delivery_project.dto.request.WeatherRequest
import com.example.delivery_project.enums.DeliveryStopStatus
import com.example.delivery_project.service.component.LocationConverter
import com.example.delivery_project.service.component.WeatherUpdater
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class DeliveryRiskRefreshService(
    private val deliveryStopRepository: DeliveryStopRepository,
    private val weatherService: WeatherService,
    private val riskAssessmentService: RiskAssessmentService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun refreshActiveStops() {
        refreshStops(deliveryStopRepository.findAllWithRiskByStatusIn(ACTIVE_STATUSES))
    }

    fun refreshPlan(planId: Long) {
        refreshStops(deliveryStopRepository.findAllWithRiskByDeliveryPlanIdAndStatusIn(planId, ACTIVE_STATUSES))
    }

    fun refreshStops(stops: Collection<DeliveryStop>) {
        if (stops.isEmpty()) {
            log.info("갱신할 활성 배송지가 없습니다.")
            return
        }
        val baseDateTime = weatherService.resolveLatestBaseDateTime()
        val stopsByCoordinate = stops.groupBy(::toGrid)
        log.info("배송 위험도 갱신 시작. stopCount={}, coordinateCount={}", stops.size, stopsByCoordinate.size)
        stopsByCoordinate.keys.forEach { updateWeather(it, baseDateTime) }
        try {
            riskAssessmentService.updateAssessments(stops.toList())
        } catch (e: Exception) {
            log.error("배송지 위험도 일괄 갱신 실패. stopIds={}", stops.map { it.id }, e)
        }
    }

    private fun updateWeather(coordinate: GridCoordinate, baseDateTime: WeatherUpdater.BaseDateTime) {
        try {
            val updated = weatherService.save(
                WeatherRequest(
                    baseDate = baseDateTime.baseDate,
                    baseTime = baseDateTime.baseTime,
                    nx = coordinate.nx,
                    ny = coordinate.ny,
                ),
            )
            if (!updated) {
                log.warn("기상 API가 실패 응답을 반환했습니다. 저장된 데이터를 사용합니다. nx={}, ny={}", coordinate.nx, coordinate.ny)
            }
        } catch (e: Exception) {
            log.error("날씨 갱신 실패. 저장된 데이터를 사용합니다. nx={}, ny={}", coordinate.nx, coordinate.ny, e)
        }
    }

    private fun toGrid(stop: DeliveryStop): GridCoordinate {
        val grid = LocationConverter.convertGridGps(LocationConverter.TO_GRID, stop.latitude, stop.longitude)
        return GridCoordinate(grid.x.toInt(), grid.y.toInt())
    }

    private companion object {
        val ACTIVE_STATUSES = listOf(DeliveryStopStatus.READY, DeliveryStopStatus.DELIVERING)
    }
}
