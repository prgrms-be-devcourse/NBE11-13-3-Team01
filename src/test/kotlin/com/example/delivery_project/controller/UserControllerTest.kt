package com.example.delivery_project.controller

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.dto.request.UserJoinRequest
import com.example.delivery_project.dto.request.UserLoginRequest
import com.example.delivery_project.enums.Role
import com.example.delivery_project.security.auth.CustomUserDetails
import com.example.delivery_project.security.jwt.JwtProperties
import com.example.delivery_project.service.TokenService
import com.example.delivery_project.service.UserService
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

class UserControllerTest {
    private val userService = mock<UserService>()
    private val tokenService = mock<TokenService>()
    private val jwtProperties = JwtProperties().apply {
        refreshTokenValidity = Duration.ofDays(7)
    }
    private val controller = UserController(userService, tokenService, jwtProperties)

    @Test
    fun `회원가입 요청을 서비스에 전달한다`() {
        val request = UserJoinRequest("driver", "password", "배송기사")

        controller.join(request)

        verify(userService).join(request)
    }

    @Test
    fun `로그인하면 AccessToken과 HttpOnly RefreshToken을 반환한다`() {
        val request = UserLoginRequest("driver", "password")
        whenever(userService.login(request))
            .thenReturn(TokenService.TokenPair("access-token", "refresh-token"))
        val response = MockHttpServletResponse()

        val result = controller.login(request, response)

        val cookie = requireNotNull(response.getCookie(CookieUtil.REFRESH_TOKEN_COOKIE))
        assertThat(result.accessToken).isEqualTo("access-token")
        assertThat(cookie.value).isEqualTo("refresh-token")
        assertThat(cookie.isHttpOnly).isTrue()
        assertThat(cookie.maxAge).isEqualTo(Duration.ofDays(7).toSeconds().toInt())
    }

    @Test
    fun `로그아웃하면 DB토큰과 쿠키를 삭제한다`() {
        val details = CustomUserDetails(user())
        val request = MockHttpServletRequest().apply {
            setCookies(Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, "refresh-token"))
        }
        val response = MockHttpServletResponse()

        controller.logout(details, request, response)

        verify(tokenService).logout(1L)
        val deleted = requireNotNull(response.getCookie(CookieUtil.REFRESH_TOKEN_COOKIE))
        assertThat(deleted.maxAge).isZero()
    }

    @Test
    fun `로그인 사용자의 프로필을 반환한다`() {
        val result = controller.getMyInfo(CustomUserDetails(user()))

        assertThat(result.id).isEqualTo(1L)
        assertThat(result.loginId).isEqualTo("driver")
        assertThat(result.name).isEqualTo("배송기사")
        assertThat(result.role).isEqualTo(Role.ROLE_DELIVERY_DRIVER)
    }

    private fun user(): User = User(
        id = 1L,
        loginId = "driver",
        password = "password",
        name = "배송기사",
        role = Role.ROLE_DELIVERY_DRIVER,
    )
}
