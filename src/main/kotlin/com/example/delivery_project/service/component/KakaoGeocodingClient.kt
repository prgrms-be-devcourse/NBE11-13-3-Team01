package com.example.delivery_project.service.component

import com.example.delivery_project.exception.ExceptionCode
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.spec.GeocodedLocation
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

@Component
class KakaoGeocodingClient(
    private val restClient: RestClient,
    @param:Value("\${kakao.local.key}") private val restApiKey: String,
) : GeocodingClient {

    override fun geocode(address: String?): GeocodedLocation {
        if (address == null || address.codePoints().allMatch(Character::isWhitespace)) {
            throw BusinessException(ExceptionCode.INVALID_INPUT, "주소가 비어 있습니다.")
        }

        val response = try {
            restClient.get()
                .uri(
                    "https://dapi.kakao.com/v2/local/search/address.json?query={query}&size=1",
                    address,
                )
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK ${restApiKey.trim { it <= ' ' }}")
                .retrieve()
                .body(KakaoAddressResponse::class.java)
        } catch (e: RestClientResponseException) {
            throw BusinessException(
                ExceptionCode.UNEXPECTED_ERROR,
                "카카오 주소 API 호출에 실패했습니다. status=${e.statusCode}, response=${e.responseBodyAsString}",
            )
        } catch (e: RestClientException) {
            throw BusinessException(
                ExceptionCode.UNEXPECTED_ERROR,
                "카카오 주소 API 호출에 실패했습니다. cause=${e.javaClass.simpleName}: ${e.message}",
            )
        }

        val documents = response?.documents
        if (documents.isNullOrEmpty()) {
            throw BusinessException(ExceptionCode.INVALID_INPUT, "검색되지 않는 주소입니다: $address")
        }

        val document = documents.first()
        return GeocodedLocation(
            requireNotNull(document.addressName),
            requireNotNull(document.y).toDouble(),
            requireNotNull(document.x).toDouble(),
        )
    }

    private data class KakaoAddressResponse(
        val documents: List<KakaoAddressDocument>?,
    )

    private data class KakaoAddressDocument(
        @param:JsonProperty("address_name") val addressName: String?,
        val x: String?,
        val y: String?,
    )
}
