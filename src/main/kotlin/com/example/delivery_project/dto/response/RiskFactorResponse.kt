package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.RiskFactor
import com.example.delivery_project.enums.RiskFactorType

data class RiskFactorResponse(
    val type: RiskFactorType,
    val description: String?,
    val score: Int,
) {
    companion object {
        fun from(factor: RiskFactor) = RiskFactorResponse(factor.type, factor.description, factor.riskScore)
    }
}
