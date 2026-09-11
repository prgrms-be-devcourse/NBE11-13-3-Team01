package com.example.delivery_project.service.component

import com.example.delivery_project.exception.global.BusinessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.web.client.RestClient
import kotlin.test.assertTrue

class KakaoGeocodingClientLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "KAKAO_LOCAL_API_KEY", matches = ".+")
    fun 실제_카카오_API로_주소를_위도와_경도로_변환한다() {
        val client = KakaoGeocodingClient(RestClient.builder().build(), System.getenv("KAKAO_LOCAL_API_KEY"))
        val result = try {
            client.geocode("한강대로 405")
        } catch (e: BusinessException) {
            throw AssertionError("실제 카카오 API 호출 실패: " + e.reason, e)
        }
        assertTrue(result.address.isNotBlank())
        assertTrue(result.latitude in 33.0..39.0)
        assertTrue(result.longitude in 124.0..132.0)
    }
}
