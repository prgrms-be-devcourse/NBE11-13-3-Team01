package com.example.delivery_project.dto.response

import com.example.delivery_project.service.component.recommendation.FeatureScore
import com.example.delivery_project.service.component.recommendation.ScoredDriver
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime
import kotlin.math.roundToLong

@Schema(description = "배송 업무에 대한 배송 기사 추천 응답")
data class DriverRecommendationResponse(
    val planId: Long,
    val departureLocation: String,
    val scheduledDepartureAt: LocalDateTime,
    @field:Schema(description = "추천 산출 시각")
    val evaluatedAt: LocalDateTime,
    @field:Schema(description = "스코어링 대상이 된 기사 수")
    val candidateCount: Int,
    @field:Schema(description = "동시 보유 한도 초과로 제외된 기사 수")
    val excludedByClaimLimit: Int,
    val recommendations: List<RecommendedDriverResponse>,
) {
    companion object {
        fun of(
            planId: Long,
            departureLocation: String,
            scheduledDepartureAt: LocalDateTime,
            evaluatedAt: LocalDateTime,
            candidateCount: Int,
            excludedByClaimLimit: Int,
            scoredDrivers: List<ScoredDriver>,
        ) = DriverRecommendationResponse(
            planId = planId,
            departureLocation = departureLocation,
            scheduledDepartureAt = scheduledDepartureAt,
            evaluatedAt = evaluatedAt,
            candidateCount = candidateCount,
            excludedByClaimLimit = excludedByClaimLimit,
            recommendations = scoredDrivers.mapIndexed { index, scored ->
                RecommendedDriverResponse.from(index + 1, scored)
            },
        )
    }
}

@Schema(description = "추천된 배송 기사")
data class RecommendedDriverResponse(
    val rank: Int,
    val driverId: Long,
    val driverLoginId: String,
    val driverName: String,
    @field:Schema(description = "적합도 점수 (0~100, 높을수록 적합)")
    val score: Int,
    val distanceMeters: Long?,
    val estimatedTravelSeconds: Long?,
    @field:Schema(description = "위치 정보가 최신인지 여부. false 이면 거리 점수를 중립 처리한 결과다.")
    val locationFresh: Boolean,
    val locationUpdatedAt: LocalDateTime?,
    val activePlans: Long,
    val remainingStops: Long,
    val remainingBoxes: Long,
    val dangerStops: Long,
    @field:Schema(description = "사람이 읽을 수 있는 추천 근거")
    val reasons: List<String>,
    @field:Schema(description = "피처별 점수 기여도")
    val featureScores: List<FeatureScoreResponse>,
) {
    companion object {
        fun from(rank: Int, scored: ScoredDriver) = RecommendedDriverResponse(
            rank = rank,
            driverId = scored.candidate.driverId,
            driverLoginId = scored.candidate.loginId,
            driverName = scored.candidate.name,
            score = scored.score,
            distanceMeters = scored.distanceMeters?.roundToLong(),
            estimatedTravelSeconds = scored.estimatedTravelSeconds,
            locationFresh = scored.locationFresh,
            locationUpdatedAt = scored.candidate.locationUpdatedAt,
            activePlans = scored.candidate.activePlans,
            remainingStops = scored.candidate.remainingStops,
            remainingBoxes = scored.candidate.remainingBoxes,
            dangerStops = scored.candidate.dangerStops,
            reasons = scored.reasons,
            featureScores = scored.featureScores.map(FeatureScoreResponse::from),
        )
    }
}

@Schema(description = "추천 점수를 구성한 피처별 기여도")
data class FeatureScoreResponse(
    val feature: String,
    val label: String,
    val rawValue: String,
    val weight: Double,
    @field:Schema(description = "0.0 ~ 1.0 로 정규화된 값 (1.0 이 가장 유리)")
    val normalized: Double,
    @field:Schema(description = "총점 100점 중 이 피처가 기여한 점수")
    val contribution: Double,
) {
    companion object {
        fun from(featureScore: FeatureScore) = FeatureScoreResponse(
            feature = featureScore.feature,
            label = featureScore.label,
            rawValue = featureScore.rawValue,
            weight = featureScore.weight,
            normalized = round(featureScore.normalized),
            contribution = round(featureScore.contribution),
        )

        private fun round(value: Double): Double = (value * 100).roundToLong() / 100.0
    }
}
