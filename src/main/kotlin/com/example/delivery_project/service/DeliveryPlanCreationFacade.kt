package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.request.CreateDeliveryItemRequest
import com.example.delivery_project.dto.request.CreateDeliveryPlanRequest
import com.example.delivery_project.dto.request.CreateDeliveryStopRequest
import com.example.delivery_project.enums.Role
import com.example.delivery_project.event.DeliveryPlanCreatedEvent
import com.example.delivery_project.exception.ExceptionCode
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.service.component.GeocodingClient
import com.example.delivery_project.service.component.LocationMapper
import com.example.delivery_project.spec.DeliveryItemSpec
import com.example.delivery_project.spec.DeliveryStopSpec
import com.example.delivery_project.spec.Location
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class DeliveryPlanCreationFacade(
    private val userRepository: UserRepository,
    private val deliveryPlanRepository: DeliveryPlanRepository,
    private val geocodingClient: GeocodingClient,
    private val locationMapper: LocationMapper,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(driverId: Long, request: CreateDeliveryPlanRequest): Long {
        val driver = getDriver(driverId)
        val departureLocation = resolveLocation(request.departureAddress)
        val stopSpecs = request.stops.map(::toStopSpec)
        val plan = DeliveryPlanFactory.create(
            driver, departureLocation, requireNotNull(request.scheduledDepartureAt), stopSpecs,
        )
        val savedPlan = deliveryPlanRepository.save(plan)
        val planId = requireNotNull(savedPlan.id)
        log.info("[PLAN] 생성 완료 planId: {}, driverId: {}", planId, driverId)
        eventPublisher.publishEvent(DeliveryPlanCreatedEvent(planId))
        return planId
    }

    private fun toStopSpec(request: CreateDeliveryStopRequest): DeliveryStopSpec =
        DeliveryStopSpec(resolveLocation(request.address), request.items.map(::toItemSpec), emptyList())

    private fun resolveLocation(address: String): Location =
        locationMapper.toLocation(geocodingClient.geocode(address))

    private fun toItemSpec(request: CreateDeliveryItemRequest): DeliveryItemSpec =
        DeliveryItemSpec(request.productName, requireNotNull(request.productType), requireNotNull(request.quantity))

    private fun getDriver(driverId: Long): User {
        val driver = userRepository.findUserById(driverId)
            ?: throw BusinessException(ExceptionCode.INVALID_INPUT, "존재하지 않는 driverId입니다: $driverId")
        if (driver.role != Role.ROLE_DELIVERY_DRIVER) {
            throw BusinessException(ExceptionCode.INVALID_INPUT, "배송 기사에게만 계획을 할당할 수 있습니다: $driverId")
        }
        return driver
    }
}
