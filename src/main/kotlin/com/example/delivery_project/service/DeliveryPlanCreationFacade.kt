package com.example.delivery_project.service

import com.example.delivery_project.config.DeliveryClaimProperties
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.request.CreateDeliveryItemRequest
import com.example.delivery_project.dto.request.CreateDeliveryPlanRequest
import com.example.delivery_project.dto.request.CreateDeliveryStopRequest
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.Role
import com.example.delivery_project.event.DeliveryPlanCreatedEvent
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.ExceptionCode
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.GeocodingClient
import com.example.delivery_project.service.component.LocationMapper
import com.example.delivery_project.service.component.recommendation.PriorityWindowAssigner
import com.example.delivery_project.spec.DeliveryItemSpec
import com.example.delivery_project.spec.DeliveryStopSpec
import com.example.delivery_project.spec.Location
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class DeliveryPlanCreationFacade(
    private val userRepository: UserRepository,
    private val deliveryPlanRepository: DeliveryPlanRepository,
    private val geocodingClient: GeocodingClient,
    private val locationMapper: LocationMapper,
    private val claimProperties: DeliveryClaimProperties,
    private val priorityWindowAssigner: PriorityWindowAssigner,
    private val openDeliveryPlanPublisher: OpenDeliveryPlanPublisher,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun createOpen(request: CreateDeliveryPlanRequest): Long {
        val plan = DeliveryPlanFactory.createOpen(
            resolveLocation(request.departureAddress),
            requireNotNull(request.scheduledDepartureAt),
            request.stops.map(::toStopSpec),
        )
        val selection = priorityWindowAssigner.select(plan, LocalDateTime.now())
        return openDeliveryPlanPublisher.publish(plan, selection)
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun create(driverId: Long, request: CreateDeliveryPlanRequest): Long {
        ensureAssignableDriver(userRepository.findUserById(driverId), driverId)
        val departureLocation = resolveLocation(request.departureAddress)
        val stopSpecs = request.stops.map(::toStopSpec)
        val driver = lockDriver(driverId)
        ensureWithinClaimLimit(driverId)

        val plan = DeliveryPlanFactory.create(
            driver,
            departureLocation,
            requireNotNull(request.scheduledDepartureAt),
            stopSpecs,
        )
        val planId = persist(plan)
        log.info("[PLAN] 생성 완료 planId: {}, driverId: {}", planId, driverId)
        return planId
    }

    private fun persist(plan: DeliveryPlan): Long {
        val savedPlan = deliveryPlanRepository.save(plan)
        val planId = requireNotNull(savedPlan.id)
        eventPublisher.publishEvent(DeliveryPlanCreatedEvent(planId))
        return planId
    }

    private fun toStopSpec(request: CreateDeliveryStopRequest): DeliveryStopSpec =
        DeliveryStopSpec(resolveLocation(request.address), request.items.map(::toItemSpec), emptyList())

    private fun resolveLocation(address: String): Location =
        locationMapper.toLocation(geocodingClient.geocode(address))

    private fun toItemSpec(request: CreateDeliveryItemRequest): DeliveryItemSpec =
        DeliveryItemSpec(request.productName, requireNotNull(request.productType), requireNotNull(request.quantity))

    private fun ensureWithinClaimLimit(driverId: Long) {
        val activeCount = deliveryPlanRepository.countByDriverIdAndStatusIn(
            driverId,
            DeliveryPlanStatus.ACTIVE_STATUSES,
        )
        if (activeCount >= claimProperties.maxActivePlans) {
            log.info(
                "[PLAN] 보유 한도 초과로 직접 할당 거부 driverId: {}, activeCount: {}, limit: {}",
                driverId, activeCount, claimProperties.maxActivePlans,
            )
            throw BusinessException(DeliveryException.DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED)
        }
    }

    private fun lockDriver(driverId: Long): User =
        ensureAssignableDriver(userRepository.findUserByIdForUpdate(driverId), driverId)

    private fun ensureAssignableDriver(driver: User?, driverId: Long): User {
        if (driver == null) {
            throw BusinessException(ExceptionCode.INVALID_INPUT, "존재하지 않는 driverId입니다: $driverId")
        }
        if (driver.role != Role.ROLE_DELIVERY_DRIVER) {
            throw BusinessException(ExceptionCode.INVALID_INPUT, "배송 기사에게만 계획을 할당할 수 있습니다: $driverId")
        }
        return driver
    }
}
