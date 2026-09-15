package com.example.delivery_project.domain.entity.delivery

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.spec.DeliveryStopSpec
import com.example.delivery_project.spec.Location
import java.time.LocalDateTime

object DeliveryPlanFactory {
    /** 관리자가 등록하는 미배정(OPEN) 배송 업무를 생성한다. */
    fun createOpen(
        departureLocation: Location,
        scheduledDepartureAt: LocalDateTime,
        stopSpecs: List<DeliveryStopSpec>,
    ): DeliveryPlan {
        val plan = DeliveryPlan.of(departureLocation, scheduledDepartureAt)
        val analyzedAt = LocalDateTime.now()

        stopSpecs.forEach { stopSpec ->
            val stop = plan.addStop(stopSpec.location, analyzedAt)
            stopSpec.items.forEach { item ->
                stop.addItem(item.productName, item.productType, item.quantity)
            }
            stopSpec.riskFactors.forEach { riskFactor ->
                stop.addRiskFactor(riskFactor.type, riskFactor.description)
            }
        }

        return plan
    }

    fun createOpen(
        departureLocation: Location,
        scheduledDepartureAt: LocalDateTime,
    ): DeliveryPlan = createOpen(departureLocation, scheduledDepartureAt, emptyList())

    /** 관리자가 특정 기사에게 직접 할당하는 경로. 생성 직후 곧바로 수령 처리한다. */
    fun create(
        driver: User,
        departureLocation: Location,
        scheduledDepartureAt: LocalDateTime,
        stopSpecs: List<DeliveryStopSpec>,
    ): DeliveryPlan = createOpen(departureLocation, scheduledDepartureAt, stopSpecs).apply { claim(driver) }

    fun create(
        driver: User,
        departureLocation: Location,
        scheduledDepartureAt: LocalDateTime,
    ): DeliveryPlan = create(driver, departureLocation, scheduledDepartureAt, emptyList())
}
