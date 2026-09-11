package com.example.delivery_project.controller

import com.example.delivery_project.security.jwt.JwtProperties
import com.example.delivery_project.service.TokenService
import com.example.delivery_project.util.CookieUtil
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration

class TokenControllerTest {
    private val tokenService = mock<TokenService>()
    private val jwtProperties = JwtProperties().apply {
        refreshTokenValidity = Duration.ofDays(7)
    }
    private val controller = TokenController(tokenService, jwtProperties)

    @Test
    fun `RefreshToken을 rotation하고 새 토큰쌍을 반환한다`() {
        val oldCookie = Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, "old-refresh-token")
        val request = MockHttpServletRequest().apply { setCookies(oldCookie) }
        val response = MockHttpServletResponse()
        whenever(tokenService.refreshToken(request.cookies))
            .thenReturn(TokenService.TokenPair("new-access-token", "new-refresh-token"))

        val result = controller.refreshToken(request, response)

        assertThat(result.accessToken).isEqualTo("new-access-token")
        assertThat(requireNotNull(response.getCookie(CookieUtil.REFRESH_TOKEN_COOKIE)).value)
            .isEqualTo("new-refresh-token")
        verify(tokenService).refreshToken(request.cookies)
    }
}
