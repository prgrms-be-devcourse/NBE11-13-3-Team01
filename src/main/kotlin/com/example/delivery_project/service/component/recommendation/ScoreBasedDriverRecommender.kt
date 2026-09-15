package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DriverRecommendationProperties
import com.example.delivery_project.util.GeoDistance
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * 결정적인 가중합 스코어링으로 배송 기사를 추천한다.
 *
 * 각 피처를 "높을수록 좋은" 0.0 ~ 1.0 값으로 정규화한 뒤 가중 평균해 0 ~ 100 점을 만든다.
 * - 거리: 설정된 상한 대비 절대 정규화 (가까울수록 높음)
 * - 업무량(진행 중 계획/남은 배송지/남은 박스/위험 배송지): 후보들 중 최댓값 대비 상대 정규화 (적을수록 높음)
 * - 위치 신선도: 오래된 위치는 거리 점수를 중립(0.5)으로 두고 별도 감점
 *
 * 상대 정규화를 쓰는 이유는 전체 기사의 업무량 수준이 시간대마다 달라지기 때문이다.
 * 절대 임계값을 박아두면 모두가 바쁜 시간대에 모든 기사가 0점이 되어 변별력이 사라진다.
 */
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
            // 동점이면 거리가 가까운 기사, 그래도 같으면 기사 ID 순으로 고정해 결과를 결정적으로 만든다.
            // 단, 거리 점수를 중립 처리한(위치가 없거나 오래된) 후보는 정렬에서도 거리를 쓰지 않는다.
            // 신뢰할 수 없는 좌표가 점수에는 반영되지 않으면서 순위만 좌우하는 모순을 막기 위해서다.
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

    /** 거리가 가까울수록 1.0 에 가깝다. 위치를 신뢰할 수 없으면 유불리가 없도록 중립값을 준다. */
    private fun normalizeDistance(distanceMeters: Double?, locationFresh: Boolean): Double {
        if (distanceMeters == null || !locationFresh) return NEUTRAL_SCORE
        val capped = distanceMeters.coerceIn(0.0, properties.maxDistanceMeters)
        return 1.0 - capped / properties.maxDistanceMeters
    }

    /** 값이 작을수록 1.0 에 가깝다. 후보 전원이 0이면 변별력이 없으므로 모두 만점 처리한다. */
    private fun normalizeLowerIsBetter(value: Long, maxValue: Long): Double {
        if (maxValue <= 0L) return 1.0
        return 1.0 - value.toDouble() / maxValue.toDouble()
    }

    /**
     * 신선도는 분 단위로 절삭하지 않고 [Duration] 으로 직접 비교한다.
     *
     * `toMinutes()` 는 초 이하를 버리므로, 임계값이 30분일 때 30분 59초도 30 으로 계산되어
     * "이 시간보다 오래되면 신선하지 않다"는 설정 의미와 최대 59초까지 어긋난다.
     *
     * 기기 시계 오차로 갱신 시각이 미래로 들어올 수 있어 절댓값으로 비교한다.
     * 몇 초 앞선 값은 신선하게 보되, 임계값을 넘길 만큼 크게 앞선 비정상 값은 신뢰하지 않는다.
     */
    private fun isLocationFresh(updatedAt: LocalDateTime?, evaluatedAt: LocalDateTime): Boolean {
        if (updatedAt == null) return false
        return elapsedSince(updatedAt, evaluatedAt) <= staleThreshold()
    }

    private fun elapsedSince(updatedAt: LocalDateTime, evaluatedAt: LocalDateTime): Duration =
        Duration.between(updatedAt, evaluatedAt).abs()

    private fun staleThreshold(): Duration = Duration.ofMinutes(properties.locationStaleMinutes)

    /**
     * 판정은 절댓값으로 하되 표시는 부호를 살린다.
     * 미래 시각을 "N분 전"으로 적으면 실제 원인(기기 시계 오차)이 가려져 운영자가 오해하게 된다.
     */
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

    /** 거리를 신뢰할 수 있을 때만 정렬 기준으로 쓴다. 나머지는 모두 같은 값으로 취급해 기사 ID 가 순서를 정한다. */
    private fun ScoredDriver.tieBreakDistance(): Double =
        if (locationFresh) distanceMeters ?: Double.MAX_VALUE else Double.MAX_VALUE

    private companion object {
        const val NEUTRAL_SCORE = 0.5
    }
}
