package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.repository.DriverLocationRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.enums.Role
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 스코어링 입력이 되는 기사 후보를 조립한다.
 *
 * 관리자용 추천 API 와 등록 시 우선권 부여가 **같은 후보 집합과 같은 스코어러**를 쓰도록 한 곳에 모았다.
 * 두 경로가 다른 기준으로 계산하면 "추천 1위인데 우선권이 없다" 같은 모순이 생긴다.
 */
@Component
class DriverCandidateLoader(
    private val userRepository: UserRepository,
    private val driverLocationRepository: DriverLocationRepository,
) {
    fun load(maxActivePlans: Int, excludeDriverId: Long? = null): DriverCandidates {
        val locationsByDriverId = driverLocationRepository.findAllWithDriverOrderByUpdatedAtDesc()
            .associateBy { requireNotNull(it.driver.id) }
        val workloads = userRepository.findDriverWorkloads(Role.ROLE_DELIVERY_DRIVER.name)

        // 한도를 채운 기사는 수령 자체가 불가능하므로 추천 후보에서도 제외한다.
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
                planId = requireNotNull(plan.id),
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
