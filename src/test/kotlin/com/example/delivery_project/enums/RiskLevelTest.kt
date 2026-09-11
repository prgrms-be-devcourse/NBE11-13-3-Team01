package com.example.delivery_project.enums

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RiskLevelTest {

    @ParameterizedTest
    @CsvSource(
        "-1, UNKNOWN",
        "0, SAFE",
        "39, SAFE",
        "40, CAUTION",
        "69, CAUTION",
        "70, DANGER",
    )
    fun `점수 경계값에 따라 위험등급을 반환한다`(score: Int, expected: RiskLevel) {
        assertThat(RiskLevel.from(score)).isEqualTo(expected)
    }
}
