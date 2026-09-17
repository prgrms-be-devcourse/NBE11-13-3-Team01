package com.example.delivery_project.service

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.config.PriorityWindowProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanPriorityDriver
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanPriorityDriverRepository
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.Role
import com.example.delivery_project.event.DeliveryPlanCreatedEvent
import com.example.delivery_project.service.component.recommendation.AiRecommendationResult
import com.example.delivery_project.service.component.recommendation.DriverCandidate
import com.example.delivery_project.service.component.recommendation.PriorityWindowSelection
import com.example.delivery_project.service.component.recommendation.ScoredDriver
import com.example.delivery_project.spec.Location
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class OpenDeliveryPlanPublisherTest {
    @Mock lateinit var deliveryPlanRepository: DeliveryPlanRepository
    @Mock lateinit var priorityDriverRepository: DeliveryPlanPriorityDriverRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var eventPublisher: ApplicationEventPublisher

    private val priorityProperties = PriorityWindowProperties().apply { seconds = 60 }
    private val claimProperties = DeliveryClaimProperties().apply { maxActivePlans = 3 }
    private val meterRegistry = SimpleMeterRegistry()
    private lateinit var publisher: OpenDeliveryPlanPublisher

    @BeforeEach
    fun setUp() {
        publisher = OpenDeliveryPlanPublisher(
            deliveryPlanRepository,
            priorityDriverRepository,
            userRepository,
            priorityProperties,
            claimProperties,
            eventPublisher,
            meterRegistry,
        )
        whenever(deliveryPlanRepository.save(any<DeliveryPlan>())).thenAnswer { invocation ->
            invocation.getArgument<DeliveryPlan>(0).also { ReflectionTestUtils.setField(it, "id", 100L) }
        }
    }

    @Test
    fun `저장 직전에도 적격인 AI 추천 기사에게만 60초 우선권을 연다`() {
        val driver1 = driver(1L)
        val driver2 = driver(2L)
        whenever(userRepository.findUserByIdForUpdate(1L)).thenReturn(driver1)
        whenever(userRepository.findUserByIdForUpdate(2L)).thenReturn(driver2)
        whenever(deliveryPlanRepository.countByDriverIdAndStatusIn(any(), eq(DeliveryPlanStatus.ACTIVE_STATUSES)))
            .thenReturn(0L)
        val plan = openPlan()
        val before = LocalDateTime.now()

        val id = publisher.publish(
            plan,
            PriorityWindowSelection(listOf(scored(2L, 93), scored(1L, 81)), AiRecommendationResult.SUCCESS),
        )

        assertThat(id).isEqualTo(100L)
        assertThat(plan.publicAt).isBetween(before.plusSeconds(59), LocalDateTime.now().plusSeconds(61))
        val priorities = argumentCaptor<Iterable<DeliveryPlanPriorityDriver>>()
        verify(priorityDriverRepository).saveAll(priorities.capture())
        assertThat(priorities.firstValue.map { it.driver.id }).containsExactly(2L, 1L)
        assertThat(priorities.firstValue.map { it.priorityRank }).containsExactly(1, 2)
        assertThat(priorities.firstValue.map { it.score }).containsExactly(93, 81)
        verify(eventPublisher).publishEvent(DeliveryPlanCreatedEvent(100L))
    }

    @Test
    fun `n8n 호출 중 후보가 보유 한도를 채우면 우선권 없이 전체 공개한다`() {
        val selected = driver(1L)
        whenever(userRepository.findUserByIdForUpdate(1L)).thenReturn(selected)
        whenever(
            deliveryPlanRepository.countByDriverIdAndStatusIn(1L, DeliveryPlanStatus.ACTIVE_STATUSES),
        ).thenReturn(3L)
        val plan = openPlan()

        publisher.publish(
            plan,
            PriorityWindowSelection(listOf(scored(1L, 95)), AiRecommendationResult.SUCCESS),
        )

        assertThat(plan.publicAt).isNull()
        verify(priorityDriverRepository, never()).saveAll(any<Iterable<DeliveryPlanPriorityDriver>>())
        verify(deliveryPlanRepository).save(plan)
        verify(eventPublisher).publishEvent(DeliveryPlanCreatedEvent(100L))
    }

    @Test
    fun `AI 실패 선택은 기사 잠금 없이 즉시 전체 공개한다`() {
        val plan = openPlan()

        publisher.publish(plan, PriorityWindowSelection(emptyList(), AiRecommendationResult.TIMEOUT))

        assertThat(plan.publicAt).isNull()
        verify(userRepository, never()).findUserByIdForUpdate(any())
        verify(priorityDriverRepository, never()).saveAll(any<Iterable<DeliveryPlanPriorityDriver>>())
    }

    private fun openPlan() = DeliveryPlanFactory.createOpen(
        Location("서울 물류센터", 37.5, 126.9),
        NOW.plusHours(1),
    )

    private fun driver(id: Long) = User.of(id, "driver$id", "password", "기사$id", Role.ROLE_DELIVERY_DRIVER)

    private fun scored(id: Long, score: Int) = ScoredDriver(
        candidate = DriverCandidate(
            driverId = id,
            loginId = "driver$id",
            name = "기사$id",
            activePlans = 0,
            remainingStops = 0,
            remainingBoxes = 0,
            dangerStops = 0,
            latitude = 37.5,
            longitude = 126.9,
            locationUpdatedAt = NOW,
        ),
        score = score,
        distanceMeters = 0.0,
        estimatedTravelSeconds = 0,
        locationFresh = true,
        featureScores = emptyList(),
        reasons = listOf("적합"),
    )

    private companion object {
        val NOW: LocalDateTime = LocalDateTime.of(2026, 9, 16, 12, 0)
    }
}
