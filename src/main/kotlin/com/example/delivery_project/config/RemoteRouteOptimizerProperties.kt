package com.example.delivery_project.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "route-optimizer.remote")
class RemoteRouteOptimizerProperties {
    var enabled: Boolean = false
    var transport: String = "http"
    var baseUrl: String = "http://localhost:8091"
    var udpHost: String = "localhost"
    var udpPort: Int = 8092
    var connectTimeout: Duration = Duration.ofMillis(200)
    var readTimeout: Duration = Duration.ofMillis(500)
}
