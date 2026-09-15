package com.example.delivery_project.service.component.recommendation

import java.time.LocalDateTime

/**
 * 특정 배송 업무에 적합한 배송 기사를 추천한다.
 *
 * 구현체를 갈아끼울 수 있도록 인터페이스로 분리한다.
 * 현재 구현은 결정적인 규칙 기반 스코어링([ScoreBasedDriverRecommender])이며,
 * LLM 기반 구현을 추가하더라도 "후보 랭킹은 스코어러가, 설명 생성은 LLM 이" 맡도록
 * 배정 자체를 비결정적 요소에 의존시키지 않는다.
 */
interface DriverRecommender {
    fun recommend(context: DriverRecommendationContext, limit: Int): List<ScoredDriver>
}

/** 추천 대상이 되는 배송 업무 정보 */
data class RecommendationTarget(
    val planId: Long,
    val departureLocation: String,
    val departureLatitude: Double,
    val departureLongitude: Double,
    val scheduledDepartureAt: LocalDateTime,
    val totalStops: Int,
    val totalBoxes: Long,
    val dangerStops: Long,
)

/** 스코어링 입력이 되는 기사 한 명의 현재 상태 */
data class DriverCandidate(
    val driverId: Long,
    val loginId: String,
    val name: String,
    val activePlans: Long,
    val remainingStops: Long,
    val remainingBoxes: Long,
    val dangerStops: Long,
    val latitude: Double?,
    val longitude: Double?,
    val locationUpdatedAt: LocalDateTime?,
)

data class DriverRecommendationContext(
    val target: RecommendationTarget,
    val candidates: List<DriverCandidate>,
    val evaluatedAt: LocalDateTime,
)

/** 피처 하나가 총점에 얼마나 기여했는지를 그대로 노출해 추천 근거를 설명 가능하게 만든다. */
data class FeatureScore(
    val feature: String,
    val label: String,
    val rawValue: String,
    val weight: Double,
    val normalized: Double,
    val contribution: Double,
)

data class ScoredDriver(
    val candidate: DriverCandidate,
    val score: Int,
    val distanceMeters: Double?,
    val estimatedTravelSeconds: Long?,
    val locationFresh: Boolean,
    val featureScores: List<FeatureScore>,
    val reasons: List<String>,
)
