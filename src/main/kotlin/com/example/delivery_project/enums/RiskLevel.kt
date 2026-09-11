package com.example.delivery_project.enums

enum class RiskLevel {
    UNKNOWN,
    SAFE,
    CAUTION,
    DANGER,
    ;

    companion object {
        fun from(riskScore: Int): RiskLevel = when {
            riskScore < 0 -> UNKNOWN
            riskScore >= 70 -> DANGER
            riskScore >= 40 -> CAUTION
            else -> SAFE
        }
    }
}
