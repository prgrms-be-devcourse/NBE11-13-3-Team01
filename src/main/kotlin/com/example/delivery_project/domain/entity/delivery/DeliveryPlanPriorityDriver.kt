package com.example.delivery_project.domain.entity.delivery

import com.example.delivery_project.domain.entity.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "delivery_plan_priority_driver",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_plan_priority_driver",
            columnNames = ["delivery_plan_id", "driver_id"],
        ),
    ],
    indexes = [Index(name = "idx_plan_priority_driver", columnList = "delivery_plan_id, driver_id")],
)
class DeliveryPlanPriorityDriver private constructor(
    deliveryPlan: DeliveryPlan,
    driver: User,
    priorityRank: Int,
    score: Int,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "delivery_plan_id", nullable = false)
    var deliveryPlan: DeliveryPlan = deliveryPlan
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "driver_id", nullable = false)
    var driver: User = driver
        protected set

    @field:Column(nullable = false)
    var priorityRank: Int = priorityRank
        protected set

    @field:Column(nullable = false)
    var score: Int = score
        protected set

    companion object {
        fun of(
            deliveryPlan: DeliveryPlan,
            driver: User,
            priorityRank: Int,
            score: Int,
        ): DeliveryPlanPriorityDriver = DeliveryPlanPriorityDriver(deliveryPlan, driver, priorityRank, score)
    }
}
