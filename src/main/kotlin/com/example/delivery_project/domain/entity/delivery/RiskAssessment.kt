package com.example.delivery_project.domain.entity.delivery

import com.example.delivery_project.enums.RiskFactorType
import com.example.delivery_project.enums.RiskLevel
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
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne
import java.time.LocalDateTime

@Entity
class RiskAssessment private constructor(
    deliveryStop: DeliveryStop,
    analyzedAt: LocalDateTime,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:OneToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "delivery_stop_id", nullable = false, unique = true)
    var deliveryStop: DeliveryStop = deliveryStop
        protected set

    @field:OneToMany(
        mappedBy = "riskAssessment",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    private var riskFactorEntities: MutableList<RiskFactor> = mutableListOf()

    val riskFactors: List<RiskFactor>
        get() = riskFactorEntities.toList()

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var level: RiskLevel = RiskLevel.UNKNOWN
        protected set

    @field:Column(nullable = false)
    var analyzedAt: LocalDateTime = analyzedAt
        protected set

    val score: Int
        get() = if (level == RiskLevel.UNKNOWN) -1 else factorScore

    internal fun isDanger(): Boolean = level == RiskLevel.DANGER

    fun markUnknown(analyzedAt: LocalDateTime) {
        riskFactorEntities.clear()
        level = RiskLevel.UNKNOWN
        this.analyzedAt = analyzedAt
    }

    fun addFactor(type: RiskFactorType, description: String?) {
        riskFactorEntities += RiskFactor.of(this, type, description)
        recalculateRiskLevel()
    }

    fun replaceFactors(types: List<RiskFactorType>, analyzedAt: LocalDateTime) {
        riskFactorEntities.clear()
        riskFactorEntities += types.map { type ->
            RiskFactor.of(this, type, type.description)
        }
        this.analyzedAt = analyzedAt
        recalculateRiskLevel()
    }

    fun updateFactors(types: List<RiskFactorType>) {
        riskFactorEntities.clear()
        types.forEach { type -> addFactor(type, type.description) }
    }

    private fun recalculateRiskLevel() {
        level = RiskLevel.from(factorScore)
    }

    private val factorScore: Int
        get() = riskFactorEntities.sumOf(RiskFactor::riskScore)

    companion object {
        fun of(stop: DeliveryStop, analyzedAt: LocalDateTime): RiskAssessment =
            RiskAssessment(stop, analyzedAt)
    }
}
