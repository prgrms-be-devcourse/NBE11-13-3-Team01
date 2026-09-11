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
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import java.time.LocalDateTime

@Entity
class DeliveryPlan private constructor(
    driver: User,
    departureLocation: String,
    departureLatitude: Double,
    departureLongitude: Double,
    scheduledDepartureAt: LocalDateTime,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "driver_id", nullable = false)
    var driver: User = driver
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

    var actualDepartureAt: LocalDateTime? = null
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var status: DeliveryPlanStatus = DeliveryPlanStatus.READY
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

    fun addStop(
        address: String,
        latitude: Double,
        longitude: Double,
        analyzedAt: LocalDateTime,
    ): DeliveryStop {
        ensureReady()
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
        ensureReady()
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

    companion object {
        internal fun of(
            driver: User,
            location: Location,
            scheduledDepartureAt: LocalDateTime,
        ): DeliveryPlan = DeliveryPlan(
            driver = driver,
            departureLocation = location.address,
            departureLatitude = location.latitude,
            departureLongitude = location.longitude,
            scheduledDepartureAt = scheduledDepartureAt,
        )
    }
}
