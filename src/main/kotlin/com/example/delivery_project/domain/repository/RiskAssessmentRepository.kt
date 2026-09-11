package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.delivery.RiskAssessment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface RiskAssessmentRepository : JpaRepository<RiskAssessment, Long> {
    @Query(
        """
        select distinct assessment
        from RiskAssessment assessment
        join fetch assessment.deliveryStop stop
        left join fetch assessment.riskFactorEntities
        where stop.id in :stopIds
        """,
    )
    fun findAllWithFactorsByDeliveryStopIdIn(stopIds: Collection<Long>): List<RiskAssessment>

    @Query(
        """
        select distinct assessment
        from RiskAssessment assessment
        left join fetch assessment.riskFactorEntities
        where assessment.deliveryStop.deliveryPlan.id = :planId
        """,
    )
    fun findAllWithFactorsByDeliveryPlanId(planId: Long): List<RiskAssessment>
}
