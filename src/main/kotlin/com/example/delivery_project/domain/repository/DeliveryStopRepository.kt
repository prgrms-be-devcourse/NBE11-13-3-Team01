package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.enums.DeliveryStopStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface DeliveryStopRepository : JpaRepository<DeliveryStop, Long> {
    @Query(
        """
        select s
        from DeliveryStop s
        left join fetch s.riskAssessment
        where s.status in :statuses
        """,
    )
    fun findAllWithRiskByStatusIn(statuses: Collection<DeliveryStopStatus>): List<DeliveryStop>

    @Query(
        """
        select s
        from DeliveryStop s
        left join fetch s.riskAssessment
        where s.deliveryPlan.id = :planId
          and s.status in :statuses
        """,
    )
    fun findAllWithRiskByDeliveryPlanIdAndStatusIn(
        planId: Long,
        statuses: Collection<DeliveryStopStatus>,
    ): List<DeliveryStop>

    @Query(
        """
        select distinct s
        from DeliveryStop s
        left join fetch s.deliveryItemEntities
        where s.deliveryPlan.id = :planId
        """,
    )
    fun findAllWithItemsByDeliveryPlanId(planId: Long): List<DeliveryStop>

    @Query(
        """
        select distinct s
        from DeliveryStop s
        left join fetch s.deliveryItemEntities
        left join fetch s.riskAssessment
        where s.id = :stopId
          and s.deliveryPlan.id = :planId
        """,
    )
    fun findDetailByIdAndPlanId(stopId: Long, planId: Long): DeliveryStop?
}
