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

    /**
     * 갱신 결과 요약. 배치가 처리 건수를 기록하고 실패 여부를 판단하는 근거로 쓴다.
     *
     * 좌표 갱신은 한 곳이 실패해도 나머지를 계속 진행한다. 저장된 날씨로 버틸 수 있기 때문이다.
     * 다만 전부 실패했다면 기상 API 자체가 죽은 것이므로 그냥 넘기면 안 된다.
     */
    data class RefreshSummary(
        val stopCount: Int,
        val coordinateCount: Int,
        val failedCoordinateCount: Int,
    ) {
        val allCoordinatesFailed: Boolean
            get() = coordinateCount > 0 && failedCoordinateCount == coordinateCount
    }

    fun refreshActiveStops(): RefreshSummary =
        refreshStops(deliveryStopRepository.findAllWithRiskByStatusIn(ACTIVE_STATUSES))

    fun refreshPlan(planId: Long): RefreshSummary =
        refreshStops(deliveryStopRepository.findAllWithRiskByDeliveryPlanIdAndStatusIn(planId, ACTIVE_STATUSES))

    fun refreshStops(stops: Collection<DeliveryStop>): RefreshSummary {
        if (stops.isEmpty()) {
            log.info("갱신할 활성 배송지가 없습니다.")
            return RefreshSummary(0, 0, 0)
        }
        val baseDateTime = weatherService.resolveLatestBaseDateTime()
        val stopsByCoordinate = stops.groupBy(::toGrid)
        log.info("배송 위험도 갱신 시작. stopCount={}, coordinateCount={}", stops.size, stopsByCoordinate.size)
        val failedCoordinates = stopsByCoordinate.keys.count { !updateWeather(it, baseDateTime) }
        try {
            riskAssessmentService.updateAssessments(stops.toList())
        } catch (e: Exception) {
            log.error("배송지 위험도 일괄 갱신 실패. stopIds={}", stops.map { it.id }, e)
        }
        return RefreshSummary(stops.size, stopsByCoordinate.size, failedCoordinates)
    }

    /** 갱신에 성공하면 true. 호출한 쪽이 실패한 좌표 수를 셀 수 있게 결과를 돌려준다. */
    private fun updateWeather(coordinate: GridCoordinate, baseDateTime: WeatherUpdater.BaseDateTime): Boolean =
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
            updated
        } catch (e: Exception) {
            log.error("날씨 갱신 실패. 저장된 데이터를 사용합니다. nx={}, ny={}", coordinate.nx, coordinate.ny, e)
            false
        }

    private fun toGrid(stop: DeliveryStop): GridCoordinate {
        val grid = LocationConverter.convertGridGps(LocationConverter.TO_GRID, stop.latitude, stop.longitude)
        return GridCoordinate(grid.x.toInt(), grid.y.toInt())
    }

    private companion object {
        val ACTIVE_STATUSES = listOf(DeliveryStopStatus.READY, DeliveryStopStatus.DELIVERING)
    }
}
