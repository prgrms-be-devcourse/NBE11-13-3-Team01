package com.example.delivery_project.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "delivery.priority-window")
class PriorityWindowProperties {
    var enabled: Boolean = true

    var seconds: Long = 60

    var driverCount: Int = 3

    var deterministicFallback: Boolean = false

    fun window(): Duration = Duration.ofSeconds(seconds)
}
