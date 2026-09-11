package com.example.delivery_project.domain.entity.delivery

import com.example.delivery_project.enums.DeliveryStopStatus
import com.example.delivery_project.enums.ProductType
import com.example.delivery_project.enums.RiskFactorType
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
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
import jakarta.persistence.OneToOne
import java.time.LocalDateTime

@Entity
class DeliveryStop private constructor(
    deliveryPlan: DeliveryPlan,
    address: String,
    latitude: Double,
    longitude: Double,
    sequence: Int,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "delivery_plan_id", nullable = false)
    var deliveryPlan: DeliveryPlan = deliveryPlan
        protected set

    @field:OneToMany(
        mappedBy = "deliveryStop",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    private var deliveryItemEntities: MutableList<DeliveryItem> = mutableListOf()

    val deliveryItems: List<DeliveryItem>
        get() = deliveryItemEntities.toList()

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var status: DeliveryStopStatus = DeliveryStopStatus.READY
        protected set

    @field:Column(nullable = false)
    var address: String = address
        protected set

    @field:Column(nullable = false)
    var latitude: Double = latitude
        protected set

    @field:Column(nullable = false)
    var longitude: Double = longitude
        protected set

    @field:Column(name = "sequence")
    var sequence: Int = sequence
        protected set

    var completedAt: LocalDateTime? = null
        protected set

    @field:OneToOne(
        mappedBy = "deliveryStop",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    lateinit var riskAssessment: RiskAssessment
        protected set

    fun addItem(
        productName: String,
        productType: ProductType,
        quantity: Int,
    ): DeliveryItem {
        if (!status.isReady()) {
            throw BusinessException(DeliveryException.DELIVERY_INVALID_PLAN_STATUS_CHANGE)
        }
        return DeliveryItem.of(this, productName, productType, quantity).also(deliveryItemEntities::add)
    }

    internal fun addRiskFactor(type: RiskFactorType, description: String?) {
        riskAssessment.addFactor(type, description)
    }

    internal fun isCompleted(): Boolean = status.isCompleted()

    internal fun isDangerStop(): Boolean = riskAssessment.isDanger()

    internal fun updateSequence(sequence: Int) {
        this.sequence = sequence
    }

    internal fun complete() {
        if (!status.isDelivering()) {
            throw BusinessException(DeliveryException.DELIVERY_INCOMPLETE_STOP)
        }
        status = DeliveryStopStatus.COMPLETED
        completedAt = LocalDateTime.now()
    }

    internal fun start() {
        if (!status.isReady()) {
            throw BusinessException(DeliveryException.DELIVERY_PLAN_NOT_READY_TO_START)
        }
        status = DeliveryStopStatus.DELIVERING
    }

    fun attachRiskAssessment(riskAssessment: RiskAssessment) {
        this.riskAssessment = riskAssessment
    }

    companion object {
        internal fun of(
            deliveryPlan: DeliveryPlan,
            address: String,
            latitude: Double,
            longitude: Double,
            sequence: Int,
            analyzedAt: LocalDateTime,
        ): DeliveryStop = DeliveryStop(deliveryPlan, address, latitude, longitude, sequence).also { stop ->
            stop.riskAssessment = RiskAssessment.of(stop, analyzedAt)
        }
    }
}
