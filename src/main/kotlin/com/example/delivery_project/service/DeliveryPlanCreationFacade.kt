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
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 관리자가 기사를 지정하지 않고 배송 업무를 등록한다.
     * 생성된 계획은 OPEN 상태로 배송 기사들의 수령 대기열에 올라가며,
     * 추천 상위 기사에게는 짧은 우선 수령 구간이 먼저 열린다.
     */
    fun createOpen(request: CreateDeliveryPlanRequest): Long {
        val plan = DeliveryPlanFactory.createOpen(
            resolveLocation(request.departureAddress),
            requireNotNull(request.scheduledDepartureAt),
            request.stops.map(::toStopSpec),
        )
        // 우선권 대상을 정하려면 계획 ID 가 필요하므로 먼저 저장한다.
        val savedPlan = deliveryPlanRepository.save(plan)
        val planId = requireNotNull(savedPlan.id)
        priorityWindowAssigner.open(savedPlan, LocalDateTime.now())
        eventPublisher.publishEvent(DeliveryPlanCreatedEvent(planId))
        log.info("[PLAN] 미배정 업무 등록 완료 planId: {}, publicAt: {}", planId, savedPlan.publicAt)
        return planId
    }

    /**
     * 관리자가 특정 기사에게 직접 할당하는 경로.
     *
     * 이 경로도 기사가 직접 수령하는 경로와 동일한 동시 보유 한도를 적용한다.
     * 그렇지 않으면 기사는 3건 제한에 걸려 수령하지 못하는데 관리자 할당으로는 4건이 되는
     * 모순이 생기고, 추천에서 제외한 기사에게 업무가 몰릴 수 있다.
     *
     * 한도 검사와 할당을 원자적으로 만들기 위해 [DeliveryPlanClaimService.claim] 과 같은 방식으로
     * 기사 행을 잠그고 READ_COMMITTED 로 실행한다. 지오코딩 같은 외부 호출은 락을 잡기 전에 끝낸다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun create(driverId: Long, request: CreateDeliveryPlanRequest): Long {
        // 1) 기사 유효성은 외부 호출 전에 확인해 잘못된 요청에 지오코딩 비용을 쓰지 않는다.
        ensureAssignableDriver(userRepository.findUserById(driverId), driverId)

        // 2) 외부 API 호출(지오코딩)은 DB 락을 잡기 전에 모두 끝낸다. 락 구간에 네트워크 I/O 를 넣지 않는다.
        val departureLocation = resolveLocation(request.departureAddress)
        val stopSpecs = request.stops.map(::toStopSpec)

        // 3) 기사 행을 잠근 뒤 한도 검사와 저장을 원자적으로 수행한다.
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
