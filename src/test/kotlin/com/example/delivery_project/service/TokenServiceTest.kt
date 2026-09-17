package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.token.RefreshToken
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.RefreshTokenRedisRepository
import com.example.delivery_project.domain.repository.RefreshTokenRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.AuthException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.security.jwt.JwtProperties
import com.example.delivery_project.security.jwt.TokenProvider
import com.example.delivery_project.security.jwt.TokenStatus
import com.example.delivery_project.security.token.RefreshTokenHasher
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class TokenServiceTest {
    @Mock lateinit var tokenProvider: TokenProvider
    @Mock lateinit var jwtProperties: JwtProperties
    @Mock lateinit var refreshTokenRepository: RefreshTokenRepository
    @Mock lateinit var refreshTokenRedisRepository: RefreshTokenRedisRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var refreshTokenHasher: RefreshTokenHasher
    @InjectMocks lateinit var tokenService: TokenService
    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user = User.of(1L, "driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER)
    }

    @Test
    fun 토큰을_발급하면_RDB에_해시를_저장하고_기존_Redis_캐시는_무효화한다() {
        givenTokenProperties()
        whenever(tokenProvider.generateToken(user, ACCESS_VALIDITY)).thenReturn("access-token")
        whenever(tokenProvider.generateToken(user, REFRESH_VALIDITY)).thenReturn("refresh-token")
        whenever(refreshTokenHasher.hash("refresh-token")).thenReturn("hashed-refresh-token")
        whenever(refreshTokenRepository.findByUserId(1L)).thenReturn(null)
        whenever(refreshTokenRepository.save(any<RefreshToken>())).thenAnswer { it.arguments[0] }

        val pair = tokenService.issueToken(user)

        assertThat(pair.accessToken).isEqualTo("access-token")
        assertThat(pair.refreshToken).isEqualTo("refresh-token")
        val captor = argumentCaptor<RefreshToken>()
        verify(refreshTokenRepository).save(captor.capture())
        assertThat(captor.firstValue.tokenHash).isEqualTo("hashed-refresh-token")
        // 새 값을 Redis에 바로 쓰지 않는다. 다음 재발급의 Cache-Aside가 RDB 값을 다시 캐싱한다.
        verify(refreshTokenRedisRepository).deleteByUserId(1L)
    }

    @Test
    fun 이미_RefreshToken_row가_있으면_새로_저장하지_않고_갱신한다() {
        givenTokenProperties()
        val existing = RefreshToken.of(user, "old-hash", LocalDateTime.now().minusDays(1))
        whenever(tokenProvider.generateToken(user, ACCESS_VALIDITY)).thenReturn("access-token")
        whenever(tokenProvider.generateToken(user, REFRESH_VALIDITY)).thenReturn("refresh-token")
        whenever(refreshTokenHasher.hash("refresh-token")).thenReturn("new-hash")
        whenever(refreshTokenRepository.findByUserId(1L)).thenReturn(existing)

        tokenService.issueToken(user)

        assertThat(existing.tokenHash).isEqualTo("new-hash")
        verify(refreshTokenRepository, never()).save(any())
        verify(refreshTokenRedisRepository).deleteByUserId(1L)
    }

    @Test
    fun 유효한_RefreshToken으로_rotation을_수행한다() {
        givenTokenProperties()
        whenever(tokenProvider.validateToken("old-refresh-token")).thenReturn(TokenStatus.VALID)
        whenever(tokenProvider.getTokenDetails("old-refresh-token")).thenReturn(user)
        whenever(refreshTokenRedisRepository.findByUserId(1L)).thenReturn("old-hash")
        whenever(refreshTokenHasher.hash("old-refresh-token")).thenReturn("old-hash")
        whenever(userRepository.findUserByIdAndDeletedAtIsNull(1L)).thenReturn(user)
        whenever(refreshTokenRepository.findByUserId(1L)).thenReturn(null)
        whenever(refreshTokenRepository.save(any<RefreshToken>())).thenAnswer { it.arguments[0] }
        whenever(tokenProvider.generateToken(user, ACCESS_VALIDITY)).thenReturn("new-access-token")
        whenever(tokenProvider.generateToken(user, REFRESH_VALIDITY)).thenReturn("new-refresh-token")
        whenever(refreshTokenHasher.hash("new-refresh-token")).thenReturn("new-hash")

        val pair = tokenService.refreshToken(cookies("old-refresh-token"))

        assertThat(pair.accessToken).isEqualTo("new-access-token")
        assertThat(pair.refreshToken).isEqualTo("new-refresh-token")
        // rotation 후 새 값을 바로 캐싱하지 않고, 기존(구) 캐시만 무효화한다.
        verify(refreshTokenRedisRepository).deleteByUserId(1L)
    }

    @Test
    fun Redis가_MISS여도_RDB에_있으면_재발급에_성공하고_Redis에_재캐싱한다() {
        givenTokenProperties()
        val stored = RefreshToken.of(user, "old-hash", LocalDateTime.now().plusDays(3))
        whenever(tokenProvider.validateToken("old-refresh-token")).thenReturn(TokenStatus.VALID)
        whenever(tokenProvider.getTokenDetails("old-refresh-token")).thenReturn(user)
        whenever(refreshTokenRedisRepository.findByUserId(1L)).thenReturn(null)
        whenever(refreshTokenRepository.findByUserId(1L)).thenReturn(stored)
        whenever(refreshTokenHasher.hash("old-refresh-token")).thenReturn("old-hash")
        whenever(userRepository.findUserByIdAndDeletedAtIsNull(1L)).thenReturn(user)
        whenever(tokenProvider.generateToken(user, ACCESS_VALIDITY)).thenReturn("new-access-token")
        whenever(tokenProvider.generateToken(user, REFRESH_VALIDITY)).thenReturn("new-refresh-token")
        whenever(refreshTokenHasher.hash("new-refresh-token")).thenReturn("new-hash")

        val pair = tokenService.refreshToken(cookies("old-refresh-token"))

        assertThat(pair.accessToken).isEqualTo("new-access-token")
        // RDB fallback으로 조회한 tokenHash를 남은 TTL로 Redis에 재캐싱
        verify(refreshTokenRedisRepository).save(eq(1L), eq("old-hash"), any())
        // 이후 rotation으로 기존 캐시가 무효화됨(새 값은 다음 재발급 때 다시 캐싱)
        verify(refreshTokenRedisRepository).deleteByUserId(1L)
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
    fun Redis와_RDB_모두에_RefreshToken이_없으면_재발급을_거부한다() {
        whenever(tokenProvider.validateToken("refresh-token")).thenReturn(TokenStatus.VALID)
        whenever(tokenProvider.getTokenDetails("refresh-token")).thenReturn(user)
        whenever(refreshTokenRedisRepository.findByUserId(1L)).thenReturn(null)
        whenever(refreshTokenRepository.findByUserId(1L)).thenReturn(null)
        assertAuthException(AuthException.INVALID_REFRESH_TOKEN) { tokenService.refreshToken(cookies("refresh-token")) }
    }

    @Test
    fun 서버에_저장된_RefreshToken_해시와_다르면_재발급을_거부한다() {
        whenever(tokenProvider.validateToken("old-refresh-token")).thenReturn(TokenStatus.VALID)
        whenever(tokenProvider.getTokenDetails("old-refresh-token")).thenReturn(user)
        whenever(refreshTokenRedisRepository.findByUserId(1L)).thenReturn("stored-hash")
        whenever(refreshTokenHasher.hash("old-refresh-token")).thenReturn("different-hash")
        assertAuthException(AuthException.INVALID_REFRESH_TOKEN) { tokenService.refreshToken(cookies("old-refresh-token")) }
    }

    @Test
    fun RefreshToken의_User가_탈퇴했거나_DB에_없으면_재발급을_거부한다() {
        whenever(tokenProvider.validateToken("refresh-token")).thenReturn(TokenStatus.VALID)
        whenever(tokenProvider.getTokenDetails("refresh-token")).thenReturn(user)
        whenever(refreshTokenRedisRepository.findByUserId(1L)).thenReturn("hash")
        whenever(refreshTokenHasher.hash("refresh-token")).thenReturn("hash")
        whenever(userRepository.findUserByIdAndDeletedAtIsNull(1L)).thenReturn(null)
        assertAuthException(AuthException.INVALID_REFRESH_TOKEN) { tokenService.refreshToken(cookies("refresh-token")) }
    }

    @Test
    fun 로그아웃하면_RDB와_Redis의_RefreshToken을_모두_삭제한다() {
        tokenService.logout(1L)
        verify(refreshTokenRepository).deleteByUserId(1L)
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
