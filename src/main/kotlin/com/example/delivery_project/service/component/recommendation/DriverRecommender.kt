package com.example.delivery_project.service.component.recommendation

import java.time.LocalDateTime

/**
 * 특정 배송 업무에 적합한 배송 기사를 추천한다.
 *
 * 구현체를 갈아끼울 수 있도록 인터페이스로 분리한다.
 * [ScoreBasedDriverRecommender]는 설명 가능한 기준 점수와 후보 풀을 만든다.
 * 관리자 조회에서는 이 순위·점수를 유지하고 n8n 근거만 병합한다. 우선권 등록에서만
 * n8n이 적격 후보 안의 우선권 대상·순위·점수를 반환하며, 실제 배정은 기사 claim이 수행한다.
 */
interface DriverRecommender {
    fun recommend(context: DriverRecommendationContext, limit: Int): List<ScoredDriver>
}

/** 추천 대상이 되는 배송 업무 정보 */
data class RecommendationTarget(
    /** 저장 전 추천에서는 아직 ID가 없을 수 있다. n8n 상관관계에는 별도 requestId를 사용한다. */
    val planId: Long?,
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
    /** 최종 추천 점수. AI 추천이 적용되면 n8n이 반환한 적합도 점수다. */
    val score: Int,
    /** AI 추천이 적용됐을 때 결정적 스코어러가 계산한 기준 점수. */
    val baseScore: Int? = null,
    val distanceMeters: Double?,
    val estimatedTravelSeconds: Long?,
    val locationFresh: Boolean,
    val featureScores: List<FeatureScore>,
    val reasons: List<String>,
)
