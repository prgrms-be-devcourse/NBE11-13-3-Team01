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

/**
 * 배송 업무 등록 시 추천 상위 기사에게 부여한 우선 수령 권한.
 *
 * `delivery_plan.public_at` 이전에는 이 목록에 있는 기사만 해당 업무를 수령할 수 있고,
 * 공개 시각이 지나면 전체 기사가 선착순으로 경쟁한다.
 */
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

    /** 1 부터 시작하는 추천 순위. 우선권 안에서도 순위를 남겨 근거를 설명할 수 있게 한다. */
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
