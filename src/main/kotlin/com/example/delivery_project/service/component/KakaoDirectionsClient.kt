package com.example.delivery_project.service.component

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

@Component
class KakaoDirectionsClient(
    private val restClient: RestClient,
    @param:Value("\${kakao.local.key}") private val restApiKey: String,
) : DrivingDirectionsClient {

    override fun findTravelDurationSeconds(
        originLatitude: Double,
        originLongitude: Double,
        destinationLatitude: Double,
        destinationLongitude: Double,
    ): Long? {
        val response = try {
            restClient.get()
                .uri(
                    "https://apis-navi.kakaomobility.com/v1/directions" +
                        "?origin={origin}&destination={destination}&priority=RECOMMEND&summary=true",
                    toCoordinate(originLatitude, originLongitude),
                    toCoordinate(destinationLatitude, destinationLongitude),
                )
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK ${restApiKey.trim { it <= ' ' }}")
                .retrieve()
                .body(KakaoDirectionsResponse::class.java)
        } catch (e: RestClientResponseException) {
            log.warn("카카오 길찾기 API 호출 실패: status={}", e.statusCode)
            return null
        } catch (e: RestClientException) {
            log.warn("카카오 길찾기 API 호출 실패: cause={}", e.javaClass.simpleName)
            return null
        }

        val routes = response?.routes
        if (routes.isNullOrEmpty()) {
            log.warn("카카오 길찾기 API가 경로를 반환하지 않았습니다.")
            return null
        }

        val route = routes.first()
        val summary = route.summary
        if (route.resultCode != 0 || summary == null || summary.duration < 0) {
            log.warn(
                "카카오 길찾기 경로 탐색 실패: code={}, message={}",
                route.resultCode,
                route.resultMessage,
            )
            return null
        }

        return summary.duration
    }

    private fun toCoordinate(latitude: Double, longitude: Double): String = "$longitude,$latitude"

    private data class KakaoDirectionsResponse(
        val routes: List<KakaoRoute>?,
    )

    private data class KakaoRoute(
        @param:JsonProperty("result_code") val resultCode: Int,
        @param:JsonProperty("result_msg") val resultMessage: String?,
        val summary: KakaoRouteSummary?,
    )

    private data class KakaoRouteSummary(
        val duration: Long,
    )

    private companion object {
        val log = LoggerFactory.getLogger(KakaoDirectionsClient::class.java)
    }
}
