package com.example.delivery_project.service.component.recommendation

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "delivery.recommendation.ai",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class DisabledAiRecommendationGateway : AiRecommendationGateway {
    override fun recommend(request: AiRecommendationRequest): AiGatewayResult =
        AiGatewayResult.Failure(AiRecommendationResult.DISABLED)
}

