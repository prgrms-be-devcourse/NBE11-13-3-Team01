package com.example.delivery_project.domain.entity.delivery

import com.example.delivery_project.enums.RiskFactorType
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne

@Entity
class RiskFactor private constructor(
    riskAssessment: RiskAssessment,
    type: RiskFactorType,
    description: String?,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(name = "risk_assessment_id", nullable = false)
    var riskAssessment: RiskAssessment = riskAssessment
        protected set

    @field:Enumerated(EnumType.STRING)
    var type: RiskFactorType = type
        protected set

    var description: String? = description
        protected set

    val riskScore: Int
        get() = type.riskScore

    companion object {
        internal fun of(
            riskAssessment: RiskAssessment,
            type: RiskFactorType,
            description: String? = null,
        ): RiskFactor = RiskFactor(riskAssessment, type, description)
    }
}
