package com.example.delivery_project.service.component

import com.example.delivery_project.dto.request.WeatherRequest
import com.example.delivery_project.dto.response.WeatherResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder

@Component
class WeatherProvider(
    private val restClient: RestClient,
    @param:Value("\${weather.api.key}") private val apiKey: String,
) {

    fun getWeather(request: WeatherRequest): WeatherResponse? {
        val uri = UriComponentsBuilder
            .fromUriString("https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtFcst")
            .queryParam("serviceKey", apiKey.trim { it <= ' ' })
            .queryParam("pageNo", 1)
            .queryParam("numOfRows", 6000)
            .queryParam("dataType", "JSON")
            .queryParam("base_date", request.baseDate)
            .queryParam("base_time", request.baseTime)
            .queryParam("nx", request.nx)
            .queryParam("ny", request.ny)
            .build(true)
            .toUri()

        val response = restClient.get()
            .uri(uri)
            .retrieve()
            .body(object : ParameterizedTypeReference<Map<String, WeatherResponse>>() {})

        return requireNotNull(response) { "기상 API 응답 본문이 없습니다." }["response"]
    }
}
