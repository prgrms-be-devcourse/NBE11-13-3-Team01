package com.example.delivery_project.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "delivery.recommendation")
class DriverRecommendationProperties {
    var limit: Int = 3

    var locationStaleMinutes: Long = 30

    var maxDistanceMeters: Double = 30_000.0

    var ai: Ai = Ai()

    var weights: Weights = Weights()

    class Weights {
        var distance: Double = 0.40
        var activePlans: Double = 0.20
        var remainingStops: Double = 0.15
        var remainingBoxes: Double = 0.10
        var dangerStops: Double = 0.05
        var locationFreshness: Double = 0.10
    }

    class Ai {
        var enabled: Boolean = false

        var webhookUrl: String = ""

        var secret: String = ""

        var connectTimeout: Duration = Duration.ofMillis(500)
        var readTimeout: Duration = Duration.ofSeconds(2)

        var candidatePoolSize: Int = 20

        var maxReasonsPerDriver: Int = 3
        var maxReasonLength: Int = 200
    }
}
