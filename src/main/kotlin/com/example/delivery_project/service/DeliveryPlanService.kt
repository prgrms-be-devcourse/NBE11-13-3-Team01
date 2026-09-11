package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.DeliveryStopRepository
import com.example.delivery_project.domain.repository.RiskAssessmentRepository
import com.example.delivery_project.dto.request.UpdateDeliveryOrderRequest
import com.example.delivery_project.dto.request.UpdateScheduledDepartureRequest
import com.example.delivery_project.dto.response.DeliveryPlanDetailResponse
import com.example.delivery_project.dto.response.DeliveryPlanSummaryResponse
import com.example.delivery_project.dto.response.DeliveryStopResponse
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class DeliveryPlanService(
    private val deliveryPlanRepository: DeliveryPlanRepository,
    private val deliveryStopRepository: DeliveryStopRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getDeliveryPlans(driverId: Long): List<DeliveryPlanSummaryResponse> {
        val plans = deliveryPlanRepository.findAllSummariesByDriverId(driverId)
        log.info("[PLAN] 목록 조회 완료 driverId: {}, planSize: {}", driverId, plans.size)
        return plans.map(DeliveryPlanSummaryResponse::from)
    }

    fun getDeliveryPlan(planId: Long, driverId: Long): DeliveryPlanDetailResponse {
        val plan = getOwnedPlanWithStopsAndRisk(planId, driverId)
        deliveryStopRepository.findAllWithItemsByDeliveryPlanId(planId)
        riskAssessmentRepository.findAllWithFactorsByDeliveryPlanId(planId)
        log.info("[PLAN] 조회 완료 planId: {}", planId)
        return DeliveryPlanDetailResponse.from(plan)
    }

    fun getDeliveryStop(planId: Long, stopId: Long, driverId: Long): DeliveryStopResponse {
        getOwnedPlan(planId, driverId)
        val stop = deliveryStopRepository.findDetailByIdAndPlanId(stopId, planId)
            ?: throw BusinessException(DeliveryException.DELIVERY_STOP_NOT_FOUND)
        riskAssessmentRepository.findAllWithFactorsByDeliveryStopIdIn(listOf(stopId))
        log.info("[STOP] 조회 완료 planId: {}, stopId: {}", planId, stopId)
        return DeliveryStopResponse.from(stop)
    }

    @Transactional
    fun changeScheduledDepartureAt(planId: Long, driverId: Long, request: UpdateScheduledDepartureRequest) {
        val plan = getOwnedPlan(planId, driverId)
        log.info("[PLAN] 예정 시간 변경 요청 planId: {}, 변경 요청 시간: {}", planId, request.scheduledDepartureAt)
        plan.updateScheduledDepartureAt(requireNotNull(request.scheduledDepartureAt))
    }

    @Transactional
    fun reorderStops(planId: Long, driverId: Long, request: UpdateDeliveryOrderRequest) {
        val plan = getOwnedPlanWithStopsAndRisk(planId, driverId)
        log.info("[PLAN] 순서 편집 요청 planId: {}", planId)
        plan.reorderStops(request.stopIds)
    }

    @Transactional
    fun start(planId: Long, driverId: Long) {
        val plan = getOwnedPlanWithStopsAndRisk(planId, driverId)
        log.info("[PLAN] 배송 시작 요청 planId: {}", planId)
        plan.start()
    }

    @Transactional
    fun completeStop(planId: Long, stopId: Long, driverId: Long) {
        val plan = getOwnedPlanWithStopsAndRisk(planId, driverId)
        log.info("[PLAN] 포인트 배송 완료처리 요청 planId: {}, stopId: {}", planId, stopId)
        plan.completeStop(stopId)
    }

    @Transactional
    fun completePlan(planId: Long, driverId: Long) {
        val plan = getOwnedPlanWithStopsAndRisk(planId, driverId)
        log.info("[PLAN] 전체 배송 완료 처리 요청 planId: {}", planId)
        plan.finish()
    }

    private fun getOwnedPlan(planId: Long, driverId: Long): DeliveryPlan =
        deliveryPlanRepository.findByIdAndDriverId(planId, driverId)
            ?: throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_FOUND)

    private fun getOwnedPlanWithStopsAndRisk(planId: Long, driverId: Long): DeliveryPlan =
        deliveryPlanRepository.findWithStopsAndRiskByIdAndDriverId(planId, driverId)
            ?: throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_FOUND)
}
