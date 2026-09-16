package com.example.delivery_project.service

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.request.CreateDeliveryItemRequest
import com.example.delivery_project.dto.request.CreateDeliveryPlanRequest
import com.example.delivery_project.dto.request.CreateDeliveryStopRequest
import com.example.delivery_project.enums.ProductType
import com.example.delivery_project.enums.RiskLevel
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.Role
import com.example.delivery_project.event.DeliveryPlanCreatedEvent
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.ExceptionCode
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.GeocodingClient
import com.example.delivery_project.service.component.LocationMapper
import com.example.delivery_project.service.component.recommendation.PriorityWindowAssigner
import com.example.delivery_project.service.component.recommendation.PriorityWindowSelection
import com.example.delivery_project.service.component.recommendation.AiRecommendationResult
import com.example.delivery_project.spec.GeocodedLocation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class DeliveryPlanCreationFacadeTest {
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var deliveryPlanRepository: DeliveryPlanRepository
    @Mock lateinit var geocodingClient: GeocodingClient
    @Mock lateinit var priorityWindowAssigner: PriorityWindowAssigner
    @Mock lateinit var openDeliveryPlanPublisher: OpenDeliveryPlanPublisher
    @Mock lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var facade: DeliveryPlanCreationFacade

    @BeforeEach
    fun setUp() {
        facade = DeliveryPlanCreationFacade(
            userRepository,
            deliveryPlanRepository,
            geocodingClient,
            LocationMapper(),
            DeliveryClaimProperties(),
            priorityWindowAssigner,
            openDeliveryPlanPublisher,
            eventPublisher,
        )
    }

    @Test
    fun 주소를_좌표로_변환해_계획을_저장하고_생성_이벤트를_발행한다() {
        val driver = User.of(7L, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)
        whenever(userRepository.findUserById(7L)).thenReturn(driver)
        whenever(userRepository.findUserByIdForUpdate(7L)).thenReturn(driver)
        whenever(geocodingClient.geocode("서울 물류센터"))
            .thenReturn(GeocodedLocation("서울 물류센터", 37.50, 126.90))
        whenever(geocodingClient.geocode("서울시청"))
            .thenReturn(GeocodedLocation("서울시청", 37.5663, 126.9779))
        whenever(deliveryPlanRepository.save(any<DeliveryPlan>())).thenAnswer {
            it.getArgument<DeliveryPlan>(0).also { plan -> ReflectionTestUtils.setField(plan, "id", 100L) }
        }
        val request = CreateDeliveryPlanRequest(
            "서울 물류센터", LocalDateTime.now().plusHours(1),
            listOf(CreateDeliveryStopRequest("서울시청", listOf(CreateDeliveryItemRequest("냉동식품", ProductType.FROZEN, 3)))),
        )

        val planId = facade.create(7L, request)
        val planCaptor = argumentCaptor<DeliveryPlan>()
        verify(deliveryPlanRepository).save(planCaptor.capture())
        val savedPlan = planCaptor.firstValue
        val savedStop = savedPlan.deliveryStops.first()
        assertThat(planId).isEqualTo(100L)
        assertThat(savedPlan.driver).isEqualTo(driver)
        assertThat(savedPlan.departureLocation).isEqualTo("서울 물류센터")
        assertThat(savedPlan.departureLatitude).isEqualTo(37.50)
        assertThat(savedPlan.totalStops).isEqualTo(1)
        assertThat(savedStop.address).isEqualTo("서울시청")
        assertThat(savedStop.deliveryItems).hasSize(1)
        assertThat(savedStop.riskAssessment.level).isEqualTo(RiskLevel.UNKNOWN)
        val eventCaptor = argumentCaptor<DeliveryPlanCreatedEvent>()
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        assertThat(eventCaptor.firstValue.planId).isEqualTo(100L)
    }

    @Test
    fun 존재하지_않는_기사로는_배송계획을_생성할_수_없다() {
        whenever(userRepository.findUserById(999L)).thenReturn(null)
        assertCannotCreate(999L)
    }

    @Test
    fun 관리자에게는_배송계획을_할당할_수_없다() {
        val admin = User.of(1L, "admin", "password", "관리자", Role.ROLE_ADMIN)
        whenever(userRepository.findUserById(1L)).thenReturn(admin)
        assertCannotCreate(1L)
    }

    @Test
    fun 동시_보유_한도를_넘긴_기사에게는_직접_할당할_수_없다() {
        val driver = User.of(7L, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)
        whenever(userRepository.findUserById(7L)).thenReturn(driver)
        whenever(userRepository.findUserByIdForUpdate(7L)).thenReturn(driver)
        whenever(geocodingClient.geocode("서울 물류센터"))
            .thenReturn(GeocodedLocation("서울 물류센터", 37.50, 126.90))
        whenever(
            deliveryPlanRepository.countByDriverIdAndStatusIn(7L, DeliveryPlanStatus.ACTIVE_STATUSES),
        ).thenReturn(3L)
        val request = CreateDeliveryPlanRequest("서울 물류센터", LocalDateTime.now().plusHours(1), emptyList())

        assertThat(assertThrows<BusinessException> { facade.create(7L, request) }.errorCode)
            .isEqualTo(DeliveryException.DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED)
        verify(deliveryPlanRepository, never()).save(any<DeliveryPlan>())
        verify(eventPublisher, never()).publishEvent(any<Any>())
    }

    @Test
    fun 미배정_업무로_등록하면_기사가_없는_OPEN_상태로_저장된다() {
        whenever(geocodingClient.geocode("서울 물류센터"))
            .thenReturn(GeocodedLocation("서울 물류센터", 37.50, 126.90))
        val selection = PriorityWindowSelection(emptyList(), AiRecommendationResult.DISABLED)
        whenever(priorityWindowAssigner.select(any(), any())).thenReturn(selection)
        whenever(openDeliveryPlanPublisher.publish(any(), eq(selection))).thenReturn(200L)
        val request = CreateDeliveryPlanRequest("서울 물류센터", LocalDateTime.now().plusHours(1), emptyList())

        val planId = facade.createOpen(request)

        val planCaptor = argumentCaptor<DeliveryPlan>()
        verify(openDeliveryPlanPublisher).publish(planCaptor.capture(), eq(selection))
        val savedPlan = planCaptor.firstValue
        assertThat(planId).isEqualTo(200L)
        assertThat(savedPlan.driver).isNull()
        assertThat(savedPlan.status).isEqualTo(DeliveryPlanStatus.OPEN)
        assertThat(savedPlan.isClaimable).isTrue()
        verify(priorityWindowAssigner).select(eq(savedPlan), any())
        verify(deliveryPlanRepository, never()).save(any<DeliveryPlan>())
        verify(userRepository, never()).findUserByIdForUpdate(any())
    }

    private fun assertCannotCreate(driverId: Long) {
        val request = CreateDeliveryPlanRequest("서울 물류센터", LocalDateTime.now().plusHours(1), emptyList())
        assertThat(assertThrows<BusinessException> { facade.create(driverId, request) }.errorCode)
            .isEqualTo(ExceptionCode.INVALID_INPUT)
        verify(geocodingClient, never()).geocode(any())
        verify(deliveryPlanRepository, never()).save(any<DeliveryPlan>())
        verify(eventPublisher, never()).publishEvent(any<Any>())
    }
}
