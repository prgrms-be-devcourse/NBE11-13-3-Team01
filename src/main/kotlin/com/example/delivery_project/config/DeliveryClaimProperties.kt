package com.example.delivery_project.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "delivery.claim")
class DeliveryClaimProperties {
    var maxActivePlans: Int = 3
}
