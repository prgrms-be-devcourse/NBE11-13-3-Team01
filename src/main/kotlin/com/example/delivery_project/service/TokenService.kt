package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.RefreshTokenRedisRepository
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

@Service
class TokenService(
    private val tokenProvider: TokenProvider,
    private val jwtProperties: JwtProperties,
    private val refreshTokenRedisRepository: RefreshTokenRedisRepository,
    private val userRepository: UserRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class TokenPair(val accessToken: String, val refreshToken: String)

    fun issueToken(user: User): TokenPair {
        val accessToken = tokenProvider.generateToken(user, jwtProperties.accessTokenValidity)
        val refreshToken = tokenProvider.generateToken(user, jwtProperties.refreshTokenValidity)
        refreshTokenRedisRepository.save(requireNotNull(user.id), refreshToken, jwtProperties.refreshTokenValidity)
        log.debug("Token issued. userId: {}", user.id)
        return TokenPair(accessToken, refreshToken)
    }

    fun refreshToken(cookies: Array<Cookie>?): TokenPair {
        val refreshToken = cookies?.firstOrNull { it.name == CookieUtil.REFRESH_TOKEN_COOKIE }?.value
            ?: throw BusinessException(AuthException.REFRESH_TOKEN_NOT_FOUND)
        when (tokenProvider.validateToken(refreshToken)) {
            TokenStatus.EXPIRED -> throw BusinessException(AuthException.EXPIRED_REFRESH_TOKEN)
            TokenStatus.INVALID -> throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)
            TokenStatus.VALID -> Unit
        }

        val userId = requireNotNull(tokenProvider.getTokenDetails(refreshToken).id)
        val storedRefreshToken = refreshTokenRedisRepository.findByUserId(userId)
            ?: throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)
        if (storedRefreshToken != refreshToken) {
            throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)
        }

        val user = userRepository.findUserById(userId)
            ?: throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)
        val tokenPair = issueToken(user)
        log.debug("Token refreshed. userId: {}", user.id)
        return tokenPair
    }

    fun logout(userId: Long) {
        refreshTokenRedisRepository.deleteByUserId(userId)
        log.info("[AUTH] 로그아웃 처리 완료 userId: {}", userId)
    }
}
