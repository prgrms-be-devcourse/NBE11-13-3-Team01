package com.example.delivery_project.dto.response

import com.example.delivery_project.domain.entity.delivery.RiskAssessment
import com.example.delivery_project.enums.RiskLevel
import java.time.LocalDateTime

data class RiskAssessmentResponse(
    val score: Int,
    val level: RiskLevel,
    val analyzedAt: LocalDateTime,
    val factors: List<RiskFactorResponse>,
) {
    companion object {
        fun from(assessment: RiskAssessment) = RiskAssessmentResponse(
            assessment.score,
            assessment.level,
            assessment.analyzedAt,
            assessment.riskFactors.map(RiskFactorResponse::from),
        )
    }
}
