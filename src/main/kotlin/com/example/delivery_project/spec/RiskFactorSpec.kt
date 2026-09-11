package com.example.delivery_project.spec

import com.example.delivery_project.enums.RiskFactorType

data class RiskFactorSpec(
    val type: RiskFactorType,
    val description: String,
)
