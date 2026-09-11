package com.example.delivery_project.service.component

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RiskFactorCalculatorTest {
    private val calculator = RiskFactorCalculator()

    @ParameterizedTest
    @CsvSource("1, true", "4, true", "5, true", "0, false", "2, false", "abc, false")
    fun 강수형태_코드로_비인지_판단한다(pty: String, expected: Boolean) {
        assertEquals(expected, calculator.isRain(pty))
    }

    @Test
    fun 강수형태가_없으면_비가_아니다() {
        assertFalse(calculator.isRain(null))
        assertFalse(calculator.isRain(" "))
    }

    @ParameterizedTest
    @CsvSource(
        "'30mm', 1, true", "'30mm 이상 50mm 미만', 1, true", "'30~50mm', 4, true",
        "'50mm 이상', 5, true", "'29.9mm', 1, false", "'1mm 미만', 1, false",
        "'강수없음', 1, false", "'50mm 이상', 0, false",
    )
    fun 시간당_강수량_30mm_이상이고_비가_오면_폭우다(rn1: String, pty: String, expected: Boolean) {
        assertEquals(expected, calculator.isHeavyRain(rn1, pty))
    }

    @ParameterizedTest
    @CsvSource("32.9, false", "33, true", "40, true", "invalid, false")
    fun 기온_33도_이상이면_폭염이다(temperature: String, expected: Boolean) {
        assertEquals(expected, calculator.isHeatWave(temperature))
    }

    @Test
    fun 기온값이_없으면_폭염이_아니다() {
        assertFalse(calculator.isHeatWave(null))
        assertFalse(calculator.isHeatWave(" "))
    }
}
