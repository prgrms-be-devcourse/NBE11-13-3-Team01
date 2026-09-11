package com.example.delivery_project.util

import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CookieUtilTest {
    @Test
    fun `HttpOnly RefreshToken 쿠키를 추가한다`() {
        val response = MockHttpServletResponse()

        CookieUtil.addCookie(response, CookieUtil.REFRESH_TOKEN_COOKIE, "refresh-token", 3600)

        val cookie = requireNotNull(response.getCookie(CookieUtil.REFRESH_TOKEN_COOKIE))
        assertThat(cookie.value).isEqualTo("refresh-token")
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.secure).isFalse()
        assertThat(cookie.path).isEqualTo("/")
        assertThat(cookie.maxAge).isEqualTo(3600)
    }

    @Test
    fun `로그아웃하면 동일한 이름의 쿠키를 만료시킨다`() {
        val request = MockHttpServletRequest().apply {
            setCookies(Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, "token"), Cookie("other", "value"))
        }
        val response = MockHttpServletResponse()

        CookieUtil.deleteCookie(request, response, CookieUtil.REFRESH_TOKEN_COOKIE)

        val deleted = requireNotNull(response.getCookie(CookieUtil.REFRESH_TOKEN_COOKIE))
        assertThat(deleted.value).isEmpty()
        assertThat(deleted.maxAge).isZero()
        assertThat(deleted.path).isEqualTo("/")
    }

    @Test
    fun `요청에 쿠키가 없어도 삭제는 실패하지 않는다`() {
        assertThatCode {
            CookieUtil.deleteCookie(
                MockHttpServletRequest(),
                MockHttpServletResponse(),
                CookieUtil.REFRESH_TOKEN_COOKIE,
            )
        }.doesNotThrowAnyException()
    }
}
