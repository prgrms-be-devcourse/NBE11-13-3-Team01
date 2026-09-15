package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.delivery.DeliveryPlanPriorityDriver
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface DeliveryPlanPriorityDriverRepository : JpaRepository<DeliveryPlanPriorityDriver, Long> {
    @Query(
        """
        select p
        from DeliveryPlanPriorityDriver p
        join fetch p.driver
        where p.deliveryPlan.id = :planId
        order by p.priorityRank asc
        """,
    )
    fun findAllByDeliveryPlanId(planId: Long): List<DeliveryPlanPriorityDriver>

    fun existsByDeliveryPlanIdAndDriverId(planId: Long, driverId: Long): Boolean

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from DeliveryPlanPriorityDriver p where p.deliveryPlan.id = :planId")
    fun deleteAllByDeliveryPlanId(planId: Long): Int
}
