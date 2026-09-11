package com.example.delivery_project.domain.entity.delivery

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.enums.RiskFactorType
import com.example.delivery_project.enums.RiskLevel
import com.example.delivery_project.spec.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class RiskAssessmentTest {

    @Test
    fun `날씨 분석 전에는 UNKNOWN과 마이너스 1점을 반환한다`() {
        val assessment = assessment()

        assertThat(assessment.level).isEqualTo(RiskLevel.UNKNOWN)
        assertThat(assessment.score).isEqualTo(-1)
    }

    @Test
    fun `날씨 분석에 성공하면 위험 요인으로 점수와 등급을 계산한다`() {
        val assessment = assessment()

        assessment.replaceFactors(
            listOf(RiskFactorType.HEAVY_RAIN, RiskFactorType.HEAT_WAVE),
            LocalDateTime.now(),
        )

        assertThat(assessment.score).isEqualTo(50)
        assertThat(assessment.level).isEqualTo(RiskLevel.CAUTION)
    }

    @Test
    fun `사용 가능한 날씨가 없으면 기존 요인을 지우고 UNKNOWN으로 변경한다`() {
        val assessment = assessment()
        assessment.replaceFactors(listOf(RiskFactorType.HEAVY_RAIN), LocalDateTime.now())

        assessment.markUnknown(LocalDateTime.now())

        assertThat(assessment.riskFactors).isEmpty()
        assertThat(assessment.level).isEqualTo(RiskLevel.UNKNOWN)
        assertThat(assessment.score).isEqualTo(-1)
    }

    private fun assessment(): RiskAssessment {
        val driver = User.of("driver", "password", "배송기사")
        val plan = DeliveryPlanFactory.create(
            driver,
            Location("서울 물류센터", 37.5665, 126.9780),
            LocalDateTime.now().plusHours(1),
        )
        return plan.addStop("서울시청", 37.5663, 126.9779, LocalDateTime.now()).riskAssessment
    }
}
