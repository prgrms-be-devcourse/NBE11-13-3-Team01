package com.example.delivery_project.service.component

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
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KakaoDirectionsClientTest {
    private lateinit var server: MockRestServiceServer
    private lateinit var client: KakaoDirectionsClient

    @BeforeEach
    fun setUp() {
        val builder = RestClient.builder()
        server = MockRestServiceServer.bindTo(builder).build()
        client = KakaoDirectionsClient(builder.build(), "test-rest-api-key")
    }

    @AfterEach
    fun verifyRequest() = server.verify()

    @Test
    fun 카카오_자동차_길찾기_응답에서_예상_소요시간을_반환한다() {
        server.expect { request ->
            val uri = request.uri
            val query = UriComponentsBuilder.fromUri(uri).build().queryParams
            assertEquals("https", uri.scheme)
            assertEquals("apis-navi.kakaomobility.com", uri.host)
            assertEquals("/v1/directions", uri.path)
            assertEquals("126.901,37.501", UriUtils.decode(requireNotNull(query.getFirst("origin")), Charsets.UTF_8))
            assertEquals("126.904,37.504", UriUtils.decode(requireNotNull(query.getFirst("destination")), Charsets.UTF_8))
            assertEquals("RECOMMEND", query.getFirst("priority"))
            assertEquals("true", query.getFirst("summary"))
        }.andExpect(method(HttpMethod.GET))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "KakaoAK test-rest-api-key"))
            .andRespond(withSuccess(
                """{"routes":[{"result_code":0,"result_msg":"길찾기 성공","summary":{"distance":10234,"duration":1125}}]}""",
                MediaType.APPLICATION_JSON,
            ))
        assertEquals(1125L, duration())
    }

    @Test
    fun 카카오가_유효한_경로를_찾지_못하면_null을_반환한다() {
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(
            """{"routes":[{"result_code":104,"result_msg":"출발지와 도착지가 동일합니다"}]}""",
            MediaType.APPLICATION_JSON,
        ))
        assertNull(duration())
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "", "{}", """{"routes":null}""", """{"routes":[]}""",
        """{"routes":[{"result_code":0}]}""",
        """{"routes":[{"result_code":0,"summary":null}]}""",
        """{"routes":[{"result_code":0,"summary":{"duration":-1}}]}""",
    ])
    fun 응답이_비었거나_경로_정보가_유효하지_않으면_null을_반환한다(response: String) {
        server.expect(method(HttpMethod.GET)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON))
        assertNull(duration())
    }

    @Test
    fun 숫자_필드가_누락되어_역직렬화에_실패하면_null을_반환한다() {
        server.expect(method(HttpMethod.GET))
            .andRespond(withSuccess("""{"routes":[{"summary":{}}]}""", MediaType.APPLICATION_JSON))
        assertNull(duration())
    }

    @Test
    fun HTTP_오류시_대체_계산을_허용한다() {
        server.expect(method(HttpMethod.GET)).andRespond(withServerError())
        assertNull(duration())
    }

    @Test
    fun 연결_오류시_대체_계산을_허용한다() {
        server.expect(method(HttpMethod.GET)).andRespond(withException(IOException("connection failed")))
        assertNull(duration())
    }

    private fun duration() = client.findTravelDurationSeconds(37.501, 126.901, 37.504, 126.904)
}
