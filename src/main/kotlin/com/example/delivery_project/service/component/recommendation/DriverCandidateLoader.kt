package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.repository.DriverLocationRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.enums.Role
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class DriverCandidateLoader(
    private val userRepository: UserRepository,
    private val driverLocationRepository: DriverLocationRepository,
) {
    fun load(maxActivePlans: Int, excludeDriverId: Long? = null): DriverCandidates {
        val locationsByDriverId = driverLocationRepository.findAllWithDriverOrderByUpdatedAtDesc()
            .associateBy { requireNotNull(it.driver.id) }
        val workloads = userRepository.findDriverWorkloads(Role.ROLE_DELIVERY_DRIVER.name)
        val excludedByClaimLimit = workloads.count { it.activePlans.toLong() >= maxActivePlans }
        val candidates = workloads
            .filter { it.driverId != excludeDriverId }
            .filter { it.activePlans.toLong() < maxActivePlans }
            .map { workload ->
                val location = locationsByDriverId[workload.driverId]
                DriverCandidate(
                    driverId = workload.driverId,
                    loginId = workload.driverLoginId,
                    name = workload.driverName,
                    activePlans = workload.activePlans.toLong(),
                    remainingStops = workload.remainingStops.toLong(),
                    remainingBoxes = workload.remainingBoxes.toLong(),
                    dangerStops = workload.dangerStops.toLong(),
                    latitude = location?.latitude,
                    longitude = location?.longitude,
                    locationUpdatedAt = location?.updatedAt,
                )
            }

        return DriverCandidates(candidates, excludedByClaimLimit)
    }

    fun toContext(plan: DeliveryPlan, candidates: List<DriverCandidate>, evaluatedAt: LocalDateTime) =
        DriverRecommendationContext(
            target = RecommendationTarget(
                planId = plan.id,
                departureLocation = plan.departureLocation,
                departureLatitude = plan.departureLatitude,
                departureLongitude = plan.departureLongitude,
                scheduledDepartureAt = plan.scheduledDepartureAt,
                totalStops = plan.totalStops,
                totalBoxes = plan.totalBoxes,
                dangerStops = plan.dangerStops,
            ),
            candidates = candidates,
            evaluatedAt = evaluatedAt,
        )
}

data class DriverCandidates(
    val candidates: List<DriverCandidate>,
    val excludedByClaimLimit: Int,
)
