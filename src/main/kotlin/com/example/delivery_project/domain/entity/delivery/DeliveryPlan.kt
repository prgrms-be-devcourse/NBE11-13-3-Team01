package com.example.delivery_project.domain.entity.delivery

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.spec.Location
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDateTime

@Entity
@Table(
    name = "delivery_plan",
    indexes = [
        Index(name = "idx_delivery_plan_status_scheduled", columnList = "status, scheduled_departure_at"),
        Index(name = "idx_delivery_plan_driver_status", columnList = "driver_id, status"),
    ],
)
class DeliveryPlan private constructor(
    departureLocation: String,
    departureLatitude: Double,
    departureLongitude: Double,
    scheduledDepartureAt: LocalDateTime,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    /**
     * 관리자가 등록한 직후에는 수령한 기사가 없으므로 null 이다.
     * 기사가 [claim] 으로 수령하는 순간에만 채워진다.
     */
    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "driver_id")
    var driver: User? = null
        protected set

    /**
     * 선착순 수령 경합에서 lost update 를 막는 2차 방어선.
     * 1차 방어선은 DeliveryPlanRepository 의 원자적 조건부 UPDATE 이다.
     */
    @field:Version
    var version: Long = 0
        protected set

    @field:OneToMany(
        mappedBy = "deliveryPlan",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    @field:OrderBy("sequence ASC")
    private var deliveryStopEntities: MutableList<DeliveryStop> = mutableListOf()

    val deliveryStops: List<DeliveryStop>
        get() = deliveryStopEntities.toList()

    @field:Column(nullable = false)
    var departureLocation: String = departureLocation
        protected set

    var departureLatitude: Double = departureLatitude
        protected set

    var departureLongitude: Double = departureLongitude
        protected set

    @field:Column(nullable = false)
    var scheduledDepartureAt: LocalDateTime = scheduledDepartureAt
        protected set

    var assignedAt: LocalDateTime? = null
        protected set

    /**
     * 전체 기사에게 공개되는 시각.
     *
     * 이 시각 전에는 [DeliveryPlanPriorityDriver] 에 등록된 추천 상위 기사만 수령할 수 있다.
     * null 이면 우선권 없이 처음부터 전체 공개된 업무다. (관리자 직접 할당, 반납된 업무, 윈도우 비활성)
     */
    var publicAt: LocalDateTime? = null
        protected set

    var actualDepartureAt: LocalDateTime? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var status: DeliveryPlanStatus = DeliveryPlanStatus.OPEN
        protected set

    @field:Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now()
        protected set

    var completedAt: LocalDateTime? = null
        protected set

    val totalStops: Int
        get() = deliveryStopEntities.size

    val remainingStops: Long
        get() = deliveryStopEntities.count { !it.isCompleted() }.toLong()

    val totalBoxes: Long
        get() = deliveryStopEntities.sumOf { stop -> stop.deliveryItems.sumOf { it.quantity.toLong() } }

    val remainingBoxes: Long
        get() = deliveryStopEntities
            .asSequence()
            .filterNot(DeliveryStop::isCompleted)
            .sumOf { stop -> stop.deliveryItems.sumOf { it.quantity.toLong() } }

    val dangerStops: Long
        get() = deliveryStopEntities.count { !it.isCompleted() && it.isDangerStop() }.toLong()

    val isFinished: Boolean
        get() = status.isCompleted()

    val isClaimable: Boolean
        get() = status.isOpen() && driver == null

    /** 아직 추천 상위 기사만 수령할 수 있는 구간인지 여부 */
    fun isPriorityWindowActive(now: LocalDateTime): Boolean = publicAt?.isAfter(now) == true

    /**
     * 추천 상위 기사에게 우선 수령 권한을 주는 구간을 연다.
     * 우선권 대상 목록은 [DeliveryPlanPriorityDriver] 로 따로 저장한다.
     */
    fun openPriorityWindow(publicAt: LocalDateTime) {
        if (!isClaimable) {
            throw BusinessException(DeliveryException.DELIVERY_PLAN_ALREADY_CLAIMED)
        }
        this.publicAt = publicAt
    }

    fun addStop(
        address: String,
        latitude: Double,
        longitude: Double,
        analyzedAt: LocalDateTime,
    ): DeliveryStop {
        ensureEditable()
        return DeliveryStop.of(
            deliveryPlan = this,
            address = address,
            latitude = latitude,
            longitude = longitude,
            sequence = deliveryStopEntities.size,
            analyzedAt = analyzedAt,
        ).also(deliveryStopEntities::add)
    }

    fun addStop(location: Location, analyzedAt: LocalDateTime): DeliveryStop =
        addStop(location.address, location.latitude, location.longitude, analyzedAt)

    /**
     * 배송 기사가 미배정 업무를 수령한다.
     * 낙관적 락(@Version)과 함께 동작하며, 도메인 차원의 불변식(미배정 + OPEN)을 강제한다.
     */
    fun claim(driver: User) {
        if (!isClaimable) {
            throw BusinessException(DeliveryException.DELIVERY_PLAN_ALREADY_CLAIMED)
        }
        this.driver = driver
        this.status = DeliveryPlanStatus.READY
        this.assignedAt = LocalDateTime.now()
    }

    /**
     * 배송 시작 전에만 수령한 업무를 다시 미배정 상태로 되돌린다.
     *
     * 반납된 업무는 우선권 윈도우를 다시 열지 않고 즉시 전체 공개한다.
     * 한 번 추천받은 기사가 반납을 반복하며 같은 업무를 계속 선점하는 것을 막기 위해서다.
     */
    fun release() {
        if (!status.isReady()) {
            throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_RELEASABLE)
        }
        this.driver = null
        this.status = DeliveryPlanStatus.OPEN
        this.assignedAt = null
        this.publicAt = null
    }

    fun isOwnedBy(driverId: Long): Boolean = driver?.id == driverId

    fun start() {
        if (!status.isReady() || deliveryStopEntities.isEmpty()) {
            throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_READY_TO_START)
        }
        status = DeliveryPlanStatus.DELIVERING
        actualDepartureAt = LocalDateTime.now()
        deliveryStopEntities.forEach(DeliveryStop::start)
    }

    fun completeStop(stopId: Long) {
        val stop = deliveryStopEntities.firstOrNull { it.id == stopId }
            ?: throw BusinessException(DeliveryException.DELIVERY_STOP_NOT_FOUND)
        stop.complete()
    }

    fun updateScheduledDepartureAt(departureAt: LocalDateTime) {
        ensureEditable()
        scheduledDepartureAt = departureAt
    }

    fun reorderStops(stopIds: List<Long>) {
        ensureReady()

        val stopMap = deliveryStopEntities.associateBy { it.id }.toMutableMap()
        val reorderedStops = stopIds.map { stopId ->
            stopMap.remove(stopId)
                ?: throw BusinessException(DeliveryException.DELIVERY_STOP_NOT_FOUND)
        }

        if (stopMap.isNotEmpty()) {
            throw BusinessException(DeliveryException.DELIVERY_INVALID_PLAN_STATUS_CHANGE)
        }

        deliveryStopEntities.clear()
        deliveryStopEntities.addAll(reorderedStops)
        deliveryStopEntities.forEachIndexed { sequence, stop -> stop.updateSequence(sequence) }
    }

    fun finish() {
        if (!status.isDelivering()) {
            throw BusinessException(DeliveryException.DELIVERY_INVALID_PLAN_STATUS_CHANGE)
        }
        if (!areAllStopsCompleted()) {
            throw BusinessException(DeliveryException.DELIVERY_INCOMPLETE_STOP)
        }
        status = DeliveryPlanStatus.COMPLETED
        completedAt = LocalDateTime.now()
    }

    fun areAllStopsCompleted(): Boolean =
        deliveryStopEntities.isNotEmpty() && deliveryStopEntities.all(DeliveryStop::isCompleted)

    private fun ensureReady() {
        if (!status.isReady()) {
            throw BusinessException(DeliveryException.DELIVERY_INVALID_PLAN_STATUS_CHANGE)
        }
    }

    /** 아직 출발하지 않은 계획(미배정 OPEN 또는 배정된 READY)만 배송지/일정 편집을 허용한다. */
    private fun ensureEditable() {
        if (!status.isOpen() && !status.isReady()) {
            throw BusinessException(DeliveryException.DELIVERY_INVALID_PLAN_STATUS_CHANGE)
        }
    }

    companion object {
        internal fun of(
            location: Location,
            scheduledDepartureAt: LocalDateTime,
        ): DeliveryPlan = DeliveryPlan(
            departureLocation = location.address,
            departureLatitude = location.latitude,
            departureLongitude = location.longitude,
            scheduledDepartureAt = scheduledDepartureAt,
        )
    }
}
