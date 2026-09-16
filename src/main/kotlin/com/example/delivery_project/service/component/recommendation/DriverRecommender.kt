package com.example.delivery_project.service.component.recommendation

import java.time.LocalDateTime

interface DriverRecommender {
    fun recommend(context: DriverRecommendationContext, limit: Int): List<ScoredDriver>
}

data class RecommendationTarget(
    val planId: Long?,
    val departureLocation: String,
    val departureLatitude: Double,
    val departureLongitude: Double,
    val scheduledDepartureAt: LocalDateTime,
    val totalStops: Int,
    val totalBoxes: Long,
    val dangerStops: Long,
)

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
    val baseScore: Int? = null,
    val distanceMeters: Double?,
    val estimatedTravelSeconds: Long?,
    val locationFresh: Boolean,
    val featureScores: List<FeatureScore>,
    val reasons: List<String>,
)
