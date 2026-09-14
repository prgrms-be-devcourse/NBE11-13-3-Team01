package com.example.delivery_project.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "delivery.recommendation")
class DriverRecommendationProperties {
    /** 기본으로 반환할 추천 기사 수 */
    var limit: Int = 3

    /** 이 시간보다 오래된 위치 정보는 신뢰하지 않고 거리 점수를 중립 처리한다. */
    var locationStaleMinutes: Long = 30

    /** 거리 정규화 상한. 이 거리 이상이면 거리 점수는 0점이다. */
    var maxDistanceMeters: Double = 30_000.0

    var weights: Weights = Weights()

    class Weights {
        var distance: Double = 0.40
        var activePlans: Double = 0.20
        var remainingStops: Double = 0.15
        var remainingBoxes: Double = 0.10
        var dangerStops: Double = 0.05
        var locationFreshness: Double = 0.10
    }
}
