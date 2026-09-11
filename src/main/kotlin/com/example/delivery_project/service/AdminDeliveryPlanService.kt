package com.example.delivery_project.service

import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.DeliveryStopRepository
import com.example.delivery_project.domain.repository.RiskAssessmentRepository
import com.example.delivery_project.dto.response.AdminDeliveryPlanDetailResponse
import com.example.delivery_project.dto.response.AdminDeliveryPlanSummaryResponse
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AdminDeliveryPlanService(
    private val deliveryPlanRepository: DeliveryPlanRepository,
    private val deliveryStopRepository: DeliveryStopRepository,
    private val riskAssessmentRepository: RiskAssessmentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getAllDeliveryPlans(): List<AdminDeliveryPlanSummaryResponse> {
        val plans = deliveryPlanRepository.findAllSummaries()
        log.info("[ADMIN][PLAN] 전체 목록 조회 완료 planSize: {}", plans.size)
        return plans.map(AdminDeliveryPlanSummaryResponse::from)
    }

    fun getDeliveryPlan(planId: Long): AdminDeliveryPlanDetailResponse {
        val plan = deliveryPlanRepository.findDetailById(planId)
            ?: throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_FOUND)
        deliveryStopRepository.findAllWithItemsByDeliveryPlanId(planId)
        riskAssessmentRepository.findAllWithFactorsByDeliveryPlanId(planId)
        log.info("[ADMIN][PLAN] 상세 조회 완료 planId: {}", planId)
        return AdminDeliveryPlanDetailResponse.from(plan)
    }
}
