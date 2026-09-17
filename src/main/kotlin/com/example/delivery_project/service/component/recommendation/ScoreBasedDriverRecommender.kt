package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DriverRecommendationProperties
import com.example.delivery_project.util.GeoDistance
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt

@Component
class ScoreBasedDriverRecommender(
    private val properties: DriverRecommendationProperties,
) : DriverRecommender {
    override fun recommend(context: DriverRecommendationContext, limit: Int): List<ScoredDriver> {
        if (context.candidates.isEmpty()) return emptyList()

        val maxActivePlans = context.candidates.maxOf { it.activePlans }
        val maxRemainingStops = context.candidates.maxOf { it.remainingStops }
        val maxRemainingBoxes = context.candidates.maxOf { it.remainingBoxes }
        val maxDangerStops = context.candidates.maxOf { it.dangerStops }

        return context.candidates
            .map { candidate ->
                score(
                    context = context,
                    candidate = candidate,
                    maxActivePlans = maxActivePlans,
                    maxRemainingStops = maxRemainingStops,
                    maxRemainingBoxes = maxRemainingBoxes,
                    maxDangerStops = maxDangerStops,
                )
            }
            .sortedWith(
                compareByDescending<ScoredDriver> { it.score }
                    .thenBy { it.tieBreakDistance() }
                    .thenBy { it.candidate.driverId },
            )
            .take(limit)
    }

    private fun score(
        context: DriverRecommendationContext,
        candidate: DriverCandidate,
        maxActivePlans: Long,
        maxRemainingStops: Long,
        maxRemainingBoxes: Long,
        maxDangerStops: Long,
    ): ScoredDriver {
        val target = context.target
        val locationUpdatedAt = candidate.locationUpdatedAt
        val latitude = candidate.latitude
        val longitude = candidate.longitude
        val locationFresh = isLocationFresh(locationUpdatedAt, context.evaluatedAt)
        val distanceMeters = if (latitude != null && longitude != null) {
            GeoDistance.haversineMeters(
                latitude, longitude,
                target.departureLatitude, target.departureLongitude,
            )
        } else {
            null
        }

        val weights = properties.weights
        val features = listOf(
            FeatureScore(
                feature = "distance",
                label = "출발지까지 거리",
                rawValue = distanceMeters?.let { "%.1fkm".format(it / 1000) } ?: "위치 정보 없음",
                weight = weights.distance,
                normalized = normalizeDistance(distanceMeters, locationFresh),
                contribution = 0.0,
            ),
            FeatureScore(
                feature = "activePlans",
                label = "진행 중 배송 업무",
                rawValue = "${candidate.activePlans}건",
                weight = weights.activePlans,
                normalized = normalizeLowerIsBetter(candidate.activePlans, maxActivePlans),
                contribution = 0.0,
            ),
            FeatureScore(
                feature = "remainingStops",
                label = "남은 배송지",
                rawValue = "${candidate.remainingStops}곳",
                weight = weights.remainingStops,
                normalized = normalizeLowerIsBetter(candidate.remainingStops, maxRemainingStops),
                contribution = 0.0,
            ),
            FeatureScore(
                feature = "remainingBoxes",
                label = "남은 박스",
                rawValue = "${candidate.remainingBoxes}박스",
                weight = weights.remainingBoxes,
                normalized = normalizeLowerIsBetter(candidate.remainingBoxes, maxRemainingBoxes),
                contribution = 0.0,
            ),
            FeatureScore(
                feature = "dangerStops",
                label = "보유 중인 위험 배송지",
                rawValue = "${candidate.dangerStops}곳",
                weight = weights.dangerStops,
                normalized = normalizeLowerIsBetter(candidate.dangerStops, maxDangerStops),
                contribution = 0.0,
            ),
            FeatureScore(
                feature = "locationFreshness",
                label = "위치 정보 신선도",
                rawValue = describeFreshness(candidate.locationUpdatedAt, context.evaluatedAt),
                weight = weights.locationFreshness,
                normalized = if (locationFresh) 1.0 else 0.0,
                contribution = 0.0,
            ),
        )

        val totalWeight = features.sumOf { it.weight }.takeIf { it > 0.0 } ?: 1.0
        val scoredFeatures = features.map { it.copy(contribution = it.weight * it.normalized / totalWeight * 100) }
        val score = scoredFeatures.sumOf { it.contribution }.roundToInt().coerceIn(0, 100)

        return ScoredDriver(
            candidate = candidate,
            score = score,
            distanceMeters = distanceMeters,
            estimatedTravelSeconds = distanceMeters?.let { GeoDistance.estimateTravelSeconds(it) },
            locationFresh = locationFresh,
            featureScores = scoredFeatures,
            reasons = buildReasons(candidate, distanceMeters, locationFresh, context.evaluatedAt),
        )
    }

    private fun normalizeDistance(distanceMeters: Double?, locationFresh: Boolean): Double {
        if (distanceMeters == null || !locationFresh) return NEUTRAL_SCORE
        val capped = distanceMeters.coerceIn(0.0, properties.maxDistanceMeters)
        return 1.0 - capped / properties.maxDistanceMeters
    }

    private fun normalizeLowerIsBetter(value: Long, maxValue: Long): Double {
        if (maxValue <= 0L) return 1.0
        return 1.0 - value.toDouble() / maxValue.toDouble()
    }

    private fun isLocationFresh(updatedAt: LocalDateTime?, evaluatedAt: LocalDateTime): Boolean {
        if (updatedAt == null) return false
        return elapsedSince(updatedAt, evaluatedAt) <= staleThreshold()
    }

    private fun elapsedSince(updatedAt: LocalDateTime, evaluatedAt: LocalDateTime): Duration =
        Duration.between(updatedAt, evaluatedAt).abs()

    private fun staleThreshold(): Duration = Duration.ofMinutes(properties.locationStaleMinutes)

    private fun describeFreshness(updatedAt: LocalDateTime?, evaluatedAt: LocalDateTime): String {
        if (updatedAt == null) return "위치 미등록"
        val elapsed = Duration.between(updatedAt, evaluatedAt)
        return if (elapsed.isNegative) {
            "서버보다 ${elapsed.abs().toMinutes()}분 앞선 시각 (기기 시계 오차 의심)"
        } else {
            "${elapsed.toMinutes()}분 전 갱신"
        }
    }

    private fun buildReasons(
        candidate: DriverCandidate,
        distanceMeters: Double?,
        locationFresh: Boolean,
        evaluatedAt: LocalDateTime,
    ): List<String> = buildList {
        val locationUpdatedAt = candidate.locationUpdatedAt
        if (locationUpdatedAt == null) {
            add("등록된 위치 정보가 없어 거리 점수를 중립 처리했습니다.")
        } else if (!locationFresh) {
            val elapsed = Duration.between(locationUpdatedAt, evaluatedAt)
            if (elapsed.isNegative) {
                add(
                    "위치 정보 시각이 서버보다 ${elapsed.abs().toMinutes()}분 앞서 있어(기기 시계 오차 의심) " +
                        "거리 점수를 중립 처리했습니다.",
                )
            } else {
                add("위치 정보가 ${elapsed.toMinutes()}분 전 기준이라 거리 점수를 중립 처리했습니다.")
            }
        } else if (distanceMeters != null) {
            add("출발지에서 약 %.1fkm 떨어져 있습니다.".format(distanceMeters / 1000))
        }
        add("현재 진행 중인 업무 ${candidate.activePlans}건, 남은 배송지 ${candidate.remainingStops}곳입니다.")
        if (candidate.dangerStops > 0) {
            add("이미 위험 배송지를 ${candidate.dangerStops}곳 보유하고 있어 감점되었습니다.")
        }
    }

    private fun ScoredDriver.tieBreakDistance(): Double =
        if (locationFresh) distanceMeters ?: Double.MAX_VALUE else Double.MAX_VALUE

    private companion object {
        const val NEUTRAL_SCORE = 0.5
    }
}
