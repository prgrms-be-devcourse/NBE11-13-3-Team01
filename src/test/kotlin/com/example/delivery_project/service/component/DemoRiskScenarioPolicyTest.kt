package com.example.delivery_project.service.component

import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.delivery.RiskAssessment
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.enums.RiskFactorType
import com.example.delivery_project.enums.RiskLevel
import com.example.delivery_project.spec.Location
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DemoRiskScenarioPolicyTest {
    private val policy = DemoRiskScenarioPolicy(true)

    @Test
    fun 배송지_5곳에_위험도_시나리오를_반복_적용한다() {
        assertScenario(1, RiskLevel.SAFE, 0)
        assertScenario(2, RiskLevel.SAFE, 20, RiskFactorType.HEAT_WAVE)
        assertScenario(3, RiskLevel.CAUTION, 40, RiskFactorType.WEATHER_WARNING)
        assertScenario(4, RiskLevel.DANGER, 70, RiskFactorType.HEAVY_RAIN, RiskFactorType.WEATHER_WARNING)
        assertScenario(5, RiskLevel.UNKNOWN, -1)
        assertScenario(6, RiskLevel.SAFE, 0)
    }

    @Test
    fun 비활성화하면_기존_위험도를_변경하지_않는다() {
        val assessment = assessment()
        assessment.replaceFactors(listOf(RiskFactorType.WEATHER_WARNING), LocalDateTime.now())
        assertFalse(DemoRiskScenarioPolicy(false).applyIfEnabled(1, assessment, LocalDateTime.now()))
        assertEquals(RiskLevel.CAUTION, assessment.level)
        assertEquals(40, assessment.score)
    }

    private fun assertScenario(stopId: Long, level: RiskLevel, score: Int, vararg factors: RiskFactorType) {
        val assessment = assessment()
        assertTrue(policy.applyIfEnabled(stopId, assessment, LocalDateTime.now()))
        assertEquals(level, assessment.level)
        assertEquals(score, assessment.score)
        assertEquals(factors.toList(), assessment.riskFactors.map { it.type })
    }

    private fun assessment(): RiskAssessment {
        val plan = DeliveryPlanFactory.create(
            User.of("driver", "password", "배송기사"),
            Location("서울 물류센터", 37.5665, 126.9780),
            LocalDateTime.now().plusHours(1),
        )
        return plan.addStop("서울시청", 37.5663, 126.9779, LocalDateTime.now()).riskAssessment
    }
}
