package com.example.delivery_project.spec

class DeliveryStopSpec(
    val location: Location,
    items: List<DeliveryItemSpec>?,
    riskFactors: List<RiskFactorSpec>?,
) {
    val items: List<DeliveryItemSpec> = items?.toList().orEmpty()
    val riskFactors: List<RiskFactorSpec> = riskFactors?.toList().orEmpty()
}
