package com.example.delivery_project.service.component

import com.example.delivery_project.exception.ExceptionCode
import com.example.delivery_project.exception.global.BusinessException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import org.springframework.web.util.UriUtils
import java.io.IOException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KakaoGeocodingClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: KakaoGeocodingClient
    private val searchAddress = "전북 삼성동 100"
    private val successResponse = """
        {"meta":{"total_count":4},"documents":[{"address_name":"전북 익산시 부송동 100",
        "y":"35.97664845766847","x":"126.99597295767953","address_type":"REGION_ADDR",
        "address":{"address_name":"전북 익산시 부송동 100"},
        "road_address":{"address_name":"전북 익산시 망산길 11-17"}}]}
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        client = KakaoGeocodingClient(builder.build(), "test-rest-api-key")
    }

    @AfterEach
    fun verifyRequest() = server.verify()

    @Test
    fun 카카오_주소_검색_응답을_주소와_위도_경도로_변환한다() {
        server.expect { request ->
            val uri = request.uri
            val query = UriComponentsBuilder.fromUri(uri).build().queryParams
            assertEquals("https", uri.scheme)
            assertEquals("dapi.kakao.com", uri.host)
            assertEquals("/v2/local/search/address.json", uri.path)
            assertEquals(searchAddress, UriUtils.decode(requireNotNull(query.getFirst("query")), Charsets.UTF_8))
            assertEquals("1", query.getFirst("size"))
        }.andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK test-rest-api-key"))
            .andRespond(withSuccess(successResponse, MediaType.APPLICATION_JSON))

        val result = client.geocode(searchAddress)
        assertEquals("전북 익산시 부송동 100", result.address)
        assertEquals(35.97664845766847, result.latitude)
        assertEquals(126.99597295767953, result.longitude)
    }

    @Test
    fun 빈_주소는_API_호출_없이_거부한다() {
        assertInputError { client.geocode(" ") }
    }

    @Test
    fun null_주소는_입력_오류로_처리한다() {
        assertInputError { client.geocode(null) }
    }

    @Test
    fun NBSP_주소는_기존처럼_API로_전달한다() {
        server.expect { request ->
            val query = UriComponentsBuilder.fromUri(request.uri).build().queryParams
            assertEquals("\u00a0", UriUtils.decode(requireNotNull(query.getFirst("query")), Charsets.UTF_8))
        }.andRespond(withSuccess(successResponse, MediaType.APPLICATION_JSON))
        assertEquals("전북 익산시 부송동 100", client.geocode("\u00a0").address)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "{}", """{"documents":null}"""])
    fun 응답_본문이나_documents_필드가_없으면_입력_오류로_처리한다(response: String) {
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
        assertInputError { client.geocode(searchAddress) }
    }

    @Test
    fun HTTP_오류는_비즈니스_오류로_변환한다() {
        server.expect(method(HttpMethod.GET)).andRespond(withServerError())
        val exception = assertFailsWith<BusinessException> { client.geocode(searchAddress) }
        assertEquals(ExceptionCode.UNEXPECTED_ERROR, exception.errorCode)
        assertContains(requireNotNull(exception.reason), "status=500")
    }

    @Test
    fun 연결_오류는_비즈니스_오류로_변환한다() {
        server.expect(method(HttpMethod.GET)).andRespond(withException(IOException("connection failed")))
        val exception = assertFailsWith<BusinessException> { client.geocode(searchAddress) }
        assertEquals(ExceptionCode.UNEXPECTED_ERROR, exception.errorCode)
        assertContains(requireNotNull(exception.reason), "cause=ResourceAccessException")
    }

    @Test
    fun 검색_결과가_없는_주소는_입력_오류로_처리한다() {
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess("""{"documents":[]}""", MediaType.APPLICATION_JSON))
        assertInputError { client.geocode("없는 주소") }
    }

    private fun assertInputError(action: () -> Unit) {
        assertEquals(ExceptionCode.INVALID_INPUT, assertFailsWith<BusinessException>(block = action).errorCode)
    }
}
