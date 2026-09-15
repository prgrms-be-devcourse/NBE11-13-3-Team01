package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DriverRecommendationProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class ScoreBasedDriverRecommenderTest {
    private val properties = DriverRecommendationProperties().apply {
        locationStaleMinutes = 30
        maxDistanceMeters = 30_000.0
    }
    private val recommender = ScoreBasedDriverRecommender(properties)

    @Test
    fun `임계값과 정확히 같은 경과 시간은 신선한 위치로 본다`() {
        assertThat(scoreWithLocationAge(minutes = 30, seconds = 0).locationFresh).isTrue()
    }

    @Test
    fun `임계값을 1초라도 넘기면 신선하지 않은 위치로 본다`() {
        assertThat(scoreWithLocationAge(minutes = 30, seconds = 1).locationFresh).isFalse()
    }

    /**
     * `Duration.toMinutes()` 로 절삭하면 30분 59초가 30 으로 계산되어 fresh 로 오판된다.
     * 초 단위까지 비교하는지 고정하는 회귀 테스트다.
     */
    @Test
    fun `임계값 직후 59초 구간도 신선하지 않은 위치로 본다`() {
        assertThat(scoreWithLocationAge(minutes = 30, seconds = 59).locationFresh).isFalse()
    }

    @Test
    fun `기기 시계가 조금 앞선 위치는 신선한 것으로 본다`() {
        assertThat(scoreWithLocationAge(minutes = 0, seconds = -10).locationFresh).isTrue()
    }

    @Test
    fun `기기 시계가 임계값을 넘게 앞선 위치는 신뢰하지 않고 사유에 방향을 밝힌다`() {
        val scored = scoreWithLocationAge(minutes = -40, seconds = 0)

        assertThat(scored.locationFresh).isFalse()
        assertThat(scored.reasons).anySatisfy { reason ->
            assertThat(reason).contains("서버보다 40분 앞서")
            assertThat(reason).doesNotContain("40분 전")
        }
    }

    @Test
    fun `위치를 신뢰할 수 없으면 거리 점수를 중립 처리한다`() {
        val fresh = scoreWithLocationAge(minutes = 0, seconds = 0)
        val stale = scoreWithLocationAge(minutes = 60, seconds = 0)

        assertThat(featureOf(fresh, "distance").normalized).isEqualTo(1.0)
        assertThat(featureOf(stale, "distance").normalized).isEqualTo(0.5)
        assertThat(featureOf(stale, "locationFreshness").normalized).isEqualTo(0.0)
    }

    @Test
    fun `위치가 없는 기사도 거리 점수는 중립으로 두고 신선도에서만 감점한다`() {
        val scored = recommender
            .recommend(contextOf(candidate(driverId = 1L, latitude = null, longitude = null, updatedAt = null)), 1)
            .single()

        assertThat(scored.distanceMeters).isNull()
        assertThat(scored.estimatedTravelSeconds).isNull()
        assertThat(featureOf(scored, "distance").normalized).isEqualTo(0.5)
        assertThat(scored.reasons).anySatisfy { assertThat(it).contains("등록된 위치 정보가 없어") }
    }

    /**
     * 점수에 반영되지 않은 stale 좌표가 순위만 좌우하면 정책과 결과가 어긋난다.
     * 동점이면 거리 대신 기사 ID 로 순서를 확정해야 한다.
     */
    @Test
    fun `오래된 좌표는 동점 정렬 기준으로 쓰이지 않는다`() {
        val staleAt = EVALUATED_AT.minusMinutes(90)
        val farButLowerId = candidate(driverId = 1L, latitude = 37.80, longitude = 127.20, updatedAt = staleAt)
        val nearButHigherId = candidate(driverId = 2L, latitude = 37.5001, longitude = 126.9001, updatedAt = staleAt)

        val ranked = recommender.recommend(contextOf(nearButHigherId, farButLowerId), 2)

        assertThat(ranked.map { it.score }.distinct()).hasSize(1)
        assertThat(ranked.map { it.candidate.driverId }).containsExactly(1L, 2L)
    }

    @Test
    fun `신선한 위치끼리는 가까운 기사가 먼저 추천된다`() {
        val far = candidate(driverId = 1L, latitude = 37.80, longitude = 127.20, updatedAt = EVALUATED_AT)
        val near = candidate(driverId = 2L, latitude = 37.5001, longitude = 126.9001, updatedAt = EVALUATED_AT)

        val ranked = recommender.recommend(contextOf(far, near), 2)

        assertThat(ranked.first().candidate.driverId).isEqualTo(2L)
        assertThat(ranked.first().score).isGreaterThan(ranked.last().score)
    }

    @Test
    fun `업무량이 적은 기사가 더 높은 점수를 받는다`() {
        val busy = candidate(driverId = 1L, activePlans = 3, remainingStops = 12, remainingBoxes = 40, dangerStops = 2)
        val idle = candidate(driverId = 2L, activePlans = 0, remainingStops = 0, remainingBoxes = 0, dangerStops = 0)

        val ranked = recommender.recommend(contextOf(busy, idle), 2)

        assertThat(ranked.first().candidate.driverId).isEqualTo(2L)
    }

    @Test
    fun `요청한 수만큼만 추천한다`() {
        val candidates = (1L..5L).map { candidate(driverId = it) }

        assertThat(recommender.recommend(contextOf(*candidates.toTypedArray()), 3)).hasSize(3)
    }

    @Test
    fun `후보가 없으면 빈 결과를 돌려준다`() {
        assertThat(recommender.recommend(contextOf(), 3)).isEmpty()
    }

    private fun scoreWithLocationAge(minutes: Long, seconds: Long): ScoredDriver {
        val updatedAt = EVALUATED_AT.minusMinutes(minutes).minusSeconds(seconds)
        return recommender.recommend(contextOf(candidate(driverId = 1L, updatedAt = updatedAt)), 1).single()
    }

    private fun featureOf(scored: ScoredDriver, feature: String): FeatureScore =
        scored.featureScores.single { it.feature == feature }

    private fun contextOf(vararg candidates: DriverCandidate) = DriverRecommendationContext(
        target = RecommendationTarget(
            planId = 1L,
            departureLocation = "서울 물류센터",
            departureLatitude = 37.50,
            departureLongitude = 126.90,
            scheduledDepartureAt = EVALUATED_AT.plusHours(1),
            totalStops = 3,
            totalBoxes = 10,
            dangerStops = 0,
        ),
        candidates = candidates.toList(),
        evaluatedAt = EVALUATED_AT,
    )

    private fun candidate(
        driverId: Long,
        activePlans: Long = 0,
        remainingStops: Long = 0,
        remainingBoxes: Long = 0,
        dangerStops: Long = 0,
        latitude: Double? = 37.50,
        longitude: Double? = 126.90,
        updatedAt: LocalDateTime? = EVALUATED_AT,
    ) = DriverCandidate(
        driverId = driverId,
        loginId = "driver$driverId",
        name = "기사$driverId",
        activePlans = activePlans,
        remainingStops = remainingStops,
        remainingBoxes = remainingBoxes,
        dangerStops = dangerStops,
        latitude = latitude,
        longitude = longitude,
        locationUpdatedAt = updatedAt,
    )

    private companion object {
        val EVALUATED_AT: LocalDateTime = LocalDateTime.of(2026, 9, 14, 12, 0, 0)
    }
}
