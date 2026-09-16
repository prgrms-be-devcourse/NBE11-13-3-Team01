package com.example.delivery_project.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "delivery.recommendation")
class DriverRecommendationProperties {
    /** 기본으로 반환할 추천 기사 수 */
    var limit: Int = 3

    /** 이 시간보다 오래된 위치 정보는 신뢰하지 않고 거리 점수를 중립 처리한다. */
    var locationStaleMinutes: Long = 30

    /** 거리 정규화 상한. 이 거리 이상이면 거리 점수는 0점이다. */
    var maxDistanceMeters: Double = 30_000.0

    /** n8n 이 적격 후보 중 우선 수령 대상을 고르는 AI 추천 설정. */
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
        /** 꺼져 있거나 호출에 실패하면 우선권 없이 처음부터 전체 공개한다. */
        var enabled: Boolean = false

        /** 사용자 입력으로 덮어쓰지 않는 고정 n8n Webhook 주소. */
        var webhookUrl: String = ""

        /** n8n Webhook 인증용 공유 secret. 로그나 payload 에 포함하지 않는다. */
        var secret: String = ""

        var connectTimeout: Duration = Duration.ofMillis(500)
        var readTimeout: Duration = Duration.ofSeconds(2)

        /** n8n 에 보내는 후보 수 상한. 결정적 스코어러로 먼저 범위를 좁힌다. */
        var candidatePoolSize: Int = 20

        var maxReasonsPerDriver: Int = 3
        var maxReasonLength: Int = 200
    }
}
