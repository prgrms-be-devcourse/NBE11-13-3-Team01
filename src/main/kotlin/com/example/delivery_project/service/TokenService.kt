package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.token.RefreshToken
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.RefreshTokenRedisRepository
import com.example.delivery_project.domain.repository.RefreshTokenRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.exception.AuthException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.security.jwt.JwtProperties
import com.example.delivery_project.security.jwt.TokenProvider
import com.example.delivery_project.security.jwt.TokenStatus
import com.example.delivery_project.util.CookieUtil
import jakarta.servlet.http.Cookie
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TokenService(
    private val tokenProvider: TokenProvider,
    private val jwtProperties: JwtProperties,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val refreshTokenRedisRepository: RefreshTokenRedisRepository,
    private val userRepository: UserRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class TokenPair(val accessToken: String, val refreshToken: String)

    @Transactional
    fun issueToken(user: User): TokenPair {
        val accessToken = tokenProvider.generateToken(user, jwtProperties.accessTokenValidity)
        val refreshToken = tokenProvider.generateToken(user, jwtProperties.refreshTokenValidity)
        // DB와 Redis 모두 저장
        saveRefreshToken(user, refreshToken)
        // refreshTokenRedisRepository.save(requireNotNull(user.id), refreshToken, jwtProperties.refreshTokenValidity)
        log.debug("Token issued. userId: {}", user.id)
        return TokenPair(accessToken, refreshToken)
    }

    // Refresh Token의 원본은 DB에 저장, 동일 값을 Redis에도 캐싱
    private fun saveRefreshToken(user: User, token: String) {
        val userId = requireNotNull(user.id)
        val storedRefreshToken = refreshTokenRepository.findByUserId(userId)

        if (storedRefreshToken == null) {
            refreshTokenRepository.save(RefreshToken.of(user, token))
        } else {
            storedRefreshToken.updateToken(token)
        }

        refreshTokenRedisRepository.save(userId, token, jwtProperties.refreshTokenValidity)
    }

    @Transactional
    fun refreshToken(cookies: Array<Cookie>?): TokenPair {
        val refreshToken = cookies?.firstOrNull { it.name == CookieUtil.REFRESH_TOKEN_COOKIE }?.value
            ?: throw BusinessException(AuthException.REFRESH_TOKEN_NOT_FOUND)

        // 1. JWT 자체 검증
        when (tokenProvider.validateToken(refreshToken)) {
            TokenStatus.EXPIRED -> throw BusinessException(AuthException.EXPIRED_REFRESH_TOKEN)
            TokenStatus.INVALID -> throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)
            TokenStatus.VALID -> Unit
        }

        // 2. 검증된 JWT에서 userId 추출
        val userId = requireNotNull(tokenProvider.getTokenDetails(refreshToken).id)

        // 3. 서버가 보유한 Refresh Token 조회
        // Redis Hit -> Redis 사용
        // Redis Miss -> DB 조회 후 Redis 캐싱
        val storedRefreshToken = findStoredRefreshToken(userId)
        // val storedRefreshToken = refreshTokenRedisRepository.findByUserId(userId)
            ?: throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)

        // 4. 요청 RT와 서버 보유 RT 비교
        if (storedRefreshToken != refreshToken) {
            throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)
        }

        // 5. 현재 DB의 User 조회
        val user = userRepository.findUserById(userId)
            ?: throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)

        // 6. Rotation
        // 새로 발급한 두 토큰 DB + Redis에 모두 저장
        val tokenPair = issueToken(user)

        log.debug("Token refreshed. userId: {}", user.id)
        return tokenPair
    }

    // Cache-Aside
    // Redis에 값이 있으면 바로 반환하고, 없을 경우 DB를 조회한 뒤 Redis에 다시 저장
    private fun findStoredRefreshToken(userId: Long): String {

        val cachedToken = refreshTokenRedisRepository.findByUserId(userId)

        if (cachedToken != null) {
            return cachedToken
        }

        val storedRefreshToken = refreshTokenRepository
                .findByUserId(userId)
                ?: throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)

        refreshTokenRedisRepository.save(
            userId,
            storedRefreshToken.token,
            jwtProperties.refreshTokenValidity,
        )

        return storedRefreshToken.token
    }

    @Transactional
    fun logout(userId: Long) {
        // TODO : 코루틴 병렬 처리
        // DB의 원본 삭제
        refreshTokenRepository.deleteByUserId(userId)
        // Redis 캐시 삭제
        refreshTokenRedisRepository.deleteByUserId(userId)

        log.info("[AUTH] 로그아웃 처리 완료 userId: {}", userId)
    }
}
