package com.example.delivery_project.domain.entity.delivery

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.spec.DeliveryStopSpec
import com.example.delivery_project.spec.Location
import java.time.LocalDateTime

object DeliveryPlanFactory {
    fun create(
        driver: User,
        departureLocation: Location,
        scheduledDepartureAt: LocalDateTime,
        stopSpecs: List<DeliveryStopSpec>,
    ): DeliveryPlan {
        val plan = DeliveryPlan.of(driver, departureLocation, scheduledDepartureAt)
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

    fun create(
        driver: User,
        departureLocation: Location,
        scheduledDepartureAt: LocalDateTime,
    ): DeliveryPlan = create(driver, departureLocation, scheduledDepartureAt, emptyList())
}
