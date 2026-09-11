package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.RefreshTokenRedisRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.AuthException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.security.jwt.JwtProperties
import com.example.delivery_project.security.jwt.TokenProvider
import com.example.delivery_project.security.jwt.TokenStatus
import com.example.delivery_project.util.CookieUtil
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration

@ExtendWith(MockitoExtension::class)
class TokenServiceTest {
    @Mock lateinit var tokenProvider: TokenProvider
    @Mock lateinit var jwtProperties: JwtProperties
    @Mock lateinit var refreshTokenRedisRepository: RefreshTokenRedisRepository
    @Mock lateinit var userRepository: UserRepository
    @InjectMocks lateinit var tokenService: TokenService
    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user = User.of(1L, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)
    }

    @Test
    fun 토큰을_발급하고_RefreshToken을_Redis에_저장한다() {
        givenTokenProperties()
        whenever(tokenProvider.generateToken(user, ACCESS_VALIDITY)).thenReturn("access-token")
        whenever(tokenProvider.generateToken(user, REFRESH_VALIDITY)).thenReturn("refresh-token")
        val pair = tokenService.issueToken(user)
        assertThat(pair.accessToken).isEqualTo("access-token")
        assertThat(pair.refreshToken).isEqualTo("refresh-token")
        verify(refreshTokenRedisRepository).save(1L, "refresh-token", REFRESH_VALIDITY)
    }

    @Test
    fun 유효한_RefreshToken으로_rotation을_수행한다() {
        givenTokenProperties()
        whenever(tokenProvider.validateToken("old-refresh-token")).thenReturn(TokenStatus.VALID)
        whenever(tokenProvider.getTokenDetails("old-refresh-token")).thenReturn(user)
        whenever(refreshTokenRedisRepository.findByUserId(1L)).thenReturn("old-refresh-token")
        whenever(userRepository.findUserById(1L)).thenReturn(user)
        whenever(tokenProvider.generateToken(user, ACCESS_VALIDITY)).thenReturn("new-access-token")
        whenever(tokenProvider.generateToken(user, REFRESH_VALIDITY)).thenReturn("new-refresh-token")
        val pair = tokenService.refreshToken(cookies("old-refresh-token"))
        assertThat(pair.accessToken).isEqualTo("new-access-token")
        assertThat(pair.refreshToken).isEqualTo("new-refresh-token")
        verify(refreshTokenRedisRepository).save(1L, "new-refresh-token", REFRESH_VALIDITY)
    }

    @Test
    fun RefreshToken_쿠키가_없으면_재발급을_거부한다() {
        assertAuthException(AuthException.REFRESH_TOKEN_NOT_FOUND) { tokenService.refreshToken(null) }
    }

    @Test
    fun 만료된_RefreshToken을_거부한다() {
        whenever(tokenProvider.validateToken("expired-token")).thenReturn(TokenStatus.EXPIRED)
        assertAuthException(AuthException.EXPIRED_REFRESH_TOKEN) { tokenService.refreshToken(cookies("expired-token")) }
    }

    @Test
    fun 위조된_RefreshToken을_거부한다() {
        whenever(tokenProvider.validateToken("invalid-token")).thenReturn(TokenStatus.INVALID)
        assertAuthException(AuthException.INVALID_REFRESH_TOKEN) { tokenService.refreshToken(cookies("invalid-token")) }
    }

    @Test
    fun Redis에_RefreshToken이_없으면_재발급을_거부한다() {
        whenever(tokenProvider.validateToken("refresh-token")).thenReturn(TokenStatus.VALID)
        whenever(tokenProvider.getTokenDetails("refresh-token")).thenReturn(user)
        whenever(refreshTokenRedisRepository.findByUserId(1L)).thenReturn(null)
        assertAuthException(AuthException.INVALID_REFRESH_TOKEN) { tokenService.refreshToken(cookies("refresh-token")) }
    }

    @Test
    fun Redis에_저장된_RefreshToken과_다르면_재발급을_거부한다() {
        whenever(tokenProvider.validateToken("old-refresh-token")).thenReturn(TokenStatus.VALID)
        whenever(tokenProvider.getTokenDetails("old-refresh-token")).thenReturn(user)
        whenever(refreshTokenRedisRepository.findByUserId(1L)).thenReturn("new-refresh-token")
        assertAuthException(AuthException.INVALID_REFRESH_TOKEN) { tokenService.refreshToken(cookies("old-refresh-token")) }
    }

    @Test
    fun RefreshToken의_User가_DB에_없으면_재발급을_거부한다() {
        whenever(tokenProvider.validateToken("refresh-token")).thenReturn(TokenStatus.VALID)
        whenever(tokenProvider.getTokenDetails("refresh-token")).thenReturn(user)
        whenever(refreshTokenRedisRepository.findByUserId(1L)).thenReturn("refresh-token")
        whenever(userRepository.findUserById(1L)).thenReturn(null)
        assertAuthException(AuthException.INVALID_REFRESH_TOKEN) { tokenService.refreshToken(cookies("refresh-token")) }
    }

    @Test
    fun 로그아웃하면_Redis의_RefreshToken을_삭제한다() {
        tokenService.logout(1L)
        verify(refreshTokenRedisRepository).deleteByUserId(1L)
    }

    private fun givenTokenProperties() {
        whenever(jwtProperties.accessTokenValidity).thenReturn(ACCESS_VALIDITY)
        whenever(jwtProperties.refreshTokenValidity).thenReturn(REFRESH_VALIDITY)
    }

    private fun cookies(token: String): Array<Cookie> = arrayOf(Cookie(CookieUtil.REFRESH_TOKEN_COOKIE, token))

    private fun assertAuthException(expected: AuthException, action: () -> Unit) {
        assertThat(assertThrows<BusinessException>(action).errorCode).isEqualTo(expected)
    }

    private companion object {
        val ACCESS_VALIDITY: Duration = Duration.ofHours(2)
        val REFRESH_VALIDITY: Duration = Duration.ofDays(7)
    }
}
