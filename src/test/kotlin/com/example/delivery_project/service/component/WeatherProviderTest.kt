package com.example.delivery_project.service.component

import com.example.delivery_project.dto.request.WeatherRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.util.UriComponentsBuilder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class WeatherProviderTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var provider: WeatherProvider

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        provider = WeatherProvider(builder.build(), " test-weather-key ")
    }

    @AfterEach
    fun verifyRequest() = server.verify()

    @Test
    fun 초단기예보_API_응답을_WeatherResponse로_변환한다() {
        server.expect { request ->
            val uri = request.uri
            val query = UriComponentsBuilder.fromUri(uri).build().queryParams
            assertEquals("apis.data.go.kr", uri.host)
            assertEquals("/1360000/VilageFcstInfoService_2.0/getUltraSrtFcst", uri.path)
            mapOf("serviceKey" to "test-weather-key", "base_date" to "20260818",
                "base_time" to "1030", "nx" to "60", "ny" to "127").forEach { (key, value) ->
                assertEquals(value, query.getFirst(key))
            }
        }.andExpect(method(HttpMethod.GET)).andRespond(withSuccess(SUCCESS_RESPONSE, MediaType.APPLICATION_JSON))

        val response = assertNotNull(provider.getWeather(request()))
        assertEquals("00", assertNotNull(response.header).resultCode)
        val items = assertNotNull(assertNotNull(response.body).items).item.orEmpty()
        assertEquals(1, items.size)
        assertEquals("T1H", items.first().category)
    }

    @Test
    fun 기상_API의_HTTP_오류를_호출자에게_전파한다() {
        server.expect { request -> assertEquals("apis.data.go.kr", request.uri.host) }
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE))
        assertFailsWith<RestClientResponseException> { provider.getWeather(request()) }
    }

    private fun request() = WeatherRequest(baseDate = "20260818", baseTime = "1030", nx = 60, ny = 127)

    private val SUCCESS_RESPONSE = """
        {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL_SERVICE"},"body":{
          "dataType":"JSON","items":{"item":[{"baseDate":"20260818","baseTime":"1030",
          "category":"T1H","fcstDate":"20260818","fcstTime":"1100","fcstValue":"33","nx":60,"ny":127}]},
          "pageNo":1,"numOfRows":10,"totalCount":1}}}
    """.trimIndent()
}
