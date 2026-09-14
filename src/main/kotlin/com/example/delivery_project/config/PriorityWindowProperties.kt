package com.example.delivery_project.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "delivery.priority-window")
class PriorityWindowProperties {
    /** 우선 수령 윈도우 사용 여부. 끄면 등록 즉시 전체 공개된다. */
    var enabled: Boolean = true

    /** 추천 상위 기사에게 독점 수령 권한을 주는 시간(초) */
    var seconds: Long = 60

    /** 우선권을 받는 상위 기사 수 */
    var driverCount: Int = 3

    fun window(): Duration = Duration.ofSeconds(seconds)
}
