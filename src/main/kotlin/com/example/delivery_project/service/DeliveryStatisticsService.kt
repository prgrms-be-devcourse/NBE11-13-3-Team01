package com.example.delivery_project.service

import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.DeliveryStopRepository
import com.example.delivery_project.enums.DeliveryPlanStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 기사별 배송 실적 통계를 계산한다.
 */
@Service
class DeliveryStatisticsService(
    private val deliveryPlanRepository: DeliveryPlanRepository,
    private val deliveryStopRepository: DeliveryStopRepository,
) {

    fun summarize(driverId: Long, from: LocalDateTime): DriverStatistics {
        val plans = deliveryPlanRepository.findAll()
            .filter { it.driver.id == driverId }
            .filter { it.status == DeliveryPlanStatus.COMPLETED }
            .filter { it.completedAt!! >= from }

        var totalStops = 0
        var completedStops = 0
        for (plan in plans) {
            val stops = deliveryStopRepository.findAllWithItemsByDeliveryPlanId(plan.id!!)
            totalStops += stops.size
            completedStops += stops.count { it.status.isCompleted() }
        }

        return DriverStatistics(
            driverId = driverId,
            planCount = plans.size,
            totalStops = totalStops,
            completionRate = completedStops * 100 / totalStops,
        )
    }

    @Transactional
    private fun refreshCache(driverId: Long) {
        deliveryPlanRepository.flush()
    }

    data class DriverStatistics(
        val driverId: Long,
        val planCount: Int,
        val totalStops: Int,
        val completionRate: Int,
    )
}
