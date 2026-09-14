package com.example.delivery_project.service.component.recommendation

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.config.PriorityWindowProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanPriorityDriver
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanPriorityDriverRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.enums.Role
import com.example.delivery_project.spec.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

class PriorityWindowAssignerTest {
    private val driverCandidateLoader = mock<DriverCandidateLoader>()
    private val driverRecommender = mock<DriverRecommender>()
    private val priorityDriverRepository = mock<DeliveryPlanPriorityDriverRepository>()
    private val userRepository = mock<UserRepository>()
    private val priorityWindowProperties = PriorityWindowProperties()
    private val claimProperties = DeliveryClaimProperties()
    private lateinit var assigner: PriorityWindowAssigner

    @BeforeEach
    fun setUp() {
        priorityWindowProperties.enabled = true
        priorityWindowProperties.seconds = 60
        priorityWindowProperties.driverCount = 2
        assigner = PriorityWindowAssigner(
            driverCandidateLoader,
            driverRecommender,
            priorityDriverRepository,
            userRepository,
            priorityWindowProperties,
            claimProperties,
        )
    }

    @Test
    fun `추천 상위 기사에게 순위와 함께 우선권을 부여하고 공개 시각을 설정한다`() {
        val plan = openPlan()
        val drivers = listOf(driver(1L), driver(2L))
        whenever(driverCandidateLoader.load(any(), anyOrNull()))
            .thenReturn(DriverCandidates(drivers.map { candidateOf(requireNotNull(it.id)) }, 0))
        whenever(driverCandidateLoader.toContext(any(), any(), any())).thenReturn(contextOf(plan))
        whenever(driverRecommender.recommend(any(), eq(2)))
            .thenReturn(listOf(scored(1L, 95), scored(2L, 80)))
        whenever(userRepository.findAllById(any<Iterable<Long>>())).thenReturn(drivers)
        whenever(priorityDriverRepository.saveAll(any<List<DeliveryPlanPriorityDriver>>()))
            .thenAnswer { it.getArgument<List<DeliveryPlanPriorityDriver>>(0) }

        assigner.open(plan, NOW)

        assertThat(plan.publicAt).isEqualTo(NOW.plusSeconds(60))
        assertThat(plan.isPriorityWindowActive(NOW)).isTrue()
        assertThat(plan.isPriorityWindowActive(NOW.plusSeconds(61))).isFalse()

        val captor = argumentCaptor<List<DeliveryPlanPriorityDriver>>()
        verify(priorityDriverRepository).saveAll(captor.capture())
        val saved = captor.firstValue
        assertThat(saved.map { it.driver.id }).containsExactly(1L, 2L)
        assertThat(saved.map { it.priorityRank }).containsExactly(1, 2)
        assertThat(saved.map { it.score }).containsExactly(95, 80)
    }

    @Test
    fun `윈도우를 끄면 우선권을 부여하지 않고 즉시 전체 공개한다`() {
        priorityWindowProperties.enabled = false
        val plan = openPlan()

        assertThat(assigner.open(plan, NOW)).isEmpty()

        assertThat(plan.publicAt).isNull()
        verify(priorityDriverRepository, never()).saveAll(any<List<DeliveryPlanPriorityDriver>>())
    }

    @Test
    fun `수령 가능한 기사가 없으면 우선권 없이 즉시 전체 공개한다`() {
        val plan = openPlan()
        whenever(driverCandidateLoader.load(any(), anyOrNull())).thenReturn(DriverCandidates(emptyList(), 3))

        assertThat(assigner.open(plan, NOW)).isEmpty()

        assertThat(plan.publicAt).isNull()
        verify(driverRecommender, never()).recommend(any(), any())
    }

    private fun openPlan(): DeliveryPlan = DeliveryPlanFactory
        .createOpen(Location("서울 물류센터", 37.50, 126.90), NOW.plusHours(1))
        .also { ReflectionTestUtils.setField(it, "id", 10L) }

    private fun driver(id: Long) = User.of(id, "driver$id", "password", "기사$id", Role.ROLE_DELIVERY_DRIVER)

    private fun candidateOf(driverId: Long) = DriverCandidate(
        driverId = driverId,
        loginId = "driver$driverId",
        name = "기사$driverId",
        activePlans = 0,
        remainingStops = 0,
        remainingBoxes = 0,
        dangerStops = 0,
        latitude = 37.50,
        longitude = 126.90,
        locationUpdatedAt = NOW,
    )

    private fun scored(driverId: Long, score: Int) = ScoredDriver(
        candidate = candidateOf(driverId),
        score = score,
        distanceMeters = 0.0,
        estimatedTravelSeconds = 1L,
        locationFresh = true,
        featureScores = emptyList(),
        reasons = emptyList(),
    )

    private fun contextOf(plan: DeliveryPlan) = DriverRecommendationContext(
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
        candidates = emptyList(),
        evaluatedAt = NOW,
    )

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 14, 12, 0, 0)
    }
}
