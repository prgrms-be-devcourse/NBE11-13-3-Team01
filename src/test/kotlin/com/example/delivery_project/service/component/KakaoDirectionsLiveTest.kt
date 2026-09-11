package com.example.delivery_project.service.component

import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.spec.GeocodedLocation
import com.fasterxml.jackson.annotation.JsonProperty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.http.HttpHeaders
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KakaoDirectionsLiveTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "KAKAO_LOCAL_API_KEY", matches = ".+")
    fun 실제_카카오_API로_주소를_좌표로_변환하고_자동차_경로를_탐색한다() {
        val key = System.getenv("KAKAO_LOCAL_API_KEY")
        val restClient = RestClient.builder().build()
        val geocodingClient = KakaoGeocodingClient(restClient, key)
        val origin = geocode(geocodingClient, "서울특별시 용산구 한강대로 405")
        val destination = geocode(geocodingClient, "부산광역시 동구 중앙대로 206")
        val response = try {
            restClient.get()
                .uri("https://apis-navi.kakaomobility.com/v1/directions?origin={origin}&destination={destination}&priority=RECOMMEND&summary=true",
                    toCoordinate(origin), toCoordinate(destination))
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + key.trim())
                .retrieve().body(KakaoDirectionsResponse::class.java)
        } catch (e: RestClientResponseException) {
            throw AssertionError("실제 카카오 길찾기 API 호출 실패: status=" + e.statusCode + ", response=" + e.responseBodyAsString, e)
        }
        val routes = assertNotNull(response).routes
        assertTrue(routes.isNotEmpty())
        val route = routes.first()
        assertEquals(0, route.resultCode, route.resultMessage)
        val summary = assertNotNull(route.summary)
        assertTrue(summary.distance > 0)
        assertTrue(summary.duration > 0)
    }

    private fun geocode(client: KakaoGeocodingClient, address: String): GeocodedLocation =
        try {
            client.geocode(address)
        } catch (e: BusinessException) {
            throw AssertionError("실제 카카오 주소 API 호출 실패: address=" + address + ", reason=" + e.reason, e)
        }

    private fun toCoordinate(location: GeocodedLocation) = location.longitude.toString() + "," + location.latitude

    private data class KakaoDirectionsResponse(val routes: List<KakaoRoute>)
    private data class KakaoRoute(
        @param:JsonProperty("result_code") val resultCode: Int,
        @param:JsonProperty("result_msg") val resultMessage: String?,
        val summary: KakaoRouteSummary?,
    )
    private data class KakaoRouteSummary(val distance: Int, val duration: Int)
}
