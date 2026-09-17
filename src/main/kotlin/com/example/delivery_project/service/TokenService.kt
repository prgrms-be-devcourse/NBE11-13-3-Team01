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
import com.example.delivery_project.security.token.RefreshTokenHasher
import com.example.delivery_project.util.CookieUtil
import jakarta.servlet.http.Cookie
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime

@Service
class TokenService(
    private val tokenProvider: TokenProvider,
    private val jwtProperties: JwtProperties,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val refreshTokenRedisRepository: RefreshTokenRedisRepository,
    private val userRepository: UserRepository,
    private val refreshTokenHasher: RefreshTokenHasher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class TokenPair(val accessToken: String, val refreshToken: String)

    @Transactional
    fun issueToken(user: User): TokenPair {
        val accessToken = tokenProvider.generateToken(user, jwtProperties.accessTokenValidity)
        val refreshToken = tokenProvider.generateToken(user, jwtProperties.refreshTokenValidity)

        // Refresh Token hash를 계산해 RDB에 반영하고, 관련 Redis 캐시는 무효화한다.
        val tokenHash = refreshTokenHasher.hash(refreshToken)
        val expiresAt = LocalDateTime.now().plus(jwtProperties.refreshTokenValidity)

        saveRefreshToken(user, tokenHash, expiresAt)
        log.debug("Token issued. userId: {}", user.id)
        return TokenPair(accessToken, refreshToken)
    }

    // RDB를 Source of Truth로 갱신한다. Redis에는 새 값을 바로 쓰지 않고 기존 캐시만 무효화한다.
    // 여기서 즉시 Redis에 새 tokenHash를 써버리면, 이 트랜잭션이 나중에 롤백될 때
    // RDB는 이전 상태로 되돌아가는데 Redis만 아직 커밋되지 않은 새 값을 갖는 상태가 될 수 있다.
    // 무효화만 해두면 커밋 이후 첫 재발급 요청의 Cache-Aside(findStoredRefreshToken)가
    // RDB의 최종 값을 안전하게 다시 캐싱한다.
    private fun saveRefreshToken(user: User, tokenHash: String, expiresAt: LocalDateTime) {
        val userId = requireNotNull(user.id)
        val storedRefreshToken = refreshTokenRepository.findByUserId(userId)

        if (storedRefreshToken == null) {
            refreshTokenRepository.save(RefreshToken.of(user, tokenHash, expiresAt))
        } else {
            storedRefreshToken.update(tokenHash, expiresAt)
        }

        evictCachedRefreshToken(userId)
    }

    private fun evictCachedRefreshToken(userId: Long) {
        runCatching { refreshTokenRedisRepository.deleteByUserId(userId) }
            .onFailure {
                log.warn("Refresh Token 캐시 무효화 실패. TTL 만료로 자연 정리됩니다. userId={}", userId, it)
            }
    }

    // 재발급
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

        // 3. 서버가 보유한 Refresh Token(이미 해싱된 상태) 조회
        // Redis Hit -> Redis 사용
        // Redis Miss -> DB 조회 후 Redis 캐싱
        val storedRefreshToken = findStoredRefreshToken(userId)

        // 4. 요청(쿠키) Refresh Token을 직접 해싱한 값과 서버 보유 Refresh Token(이미 해싱된 상태) 비교
        val tokenHash = refreshTokenHasher.hash(refreshToken)
        if (storedRefreshToken != tokenHash) {
            throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)
        }

        // 5. 현재 DB의 User 조회
        val user = userRepository.findUserByIdAndDeletedAtIsNull(userId)
            ?: throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)

        // 6. Rotation
        // 새로 발급한 Refresh Token hash를 RDB에 반영하고 기존 Redis 캐시는 무효화한다.
        val tokenPair = issueToken(user)

        log.debug("Token refreshed. userId: {}", user.id)
        return tokenPair
    }

    // Cache-Aside
    // Redis에 값이 있으면 바로 반환하고, 없을 경우 DB를 조회한 뒤 Redis에 다시 저장
    private fun findStoredRefreshToken(userId: Long): String {

        val cachedToken = refreshTokenRedisRepository.findByUserId(userId)

        // Cache Hit
        if (cachedToken != null) {
            return cachedToken
        }

        // Cache Miss -> DB 조회 후 Redis 캐싱
        val storedRefreshToken = refreshTokenRepository
                .findByUserId(userId)
                ?: throw BusinessException(AuthException.INVALID_REFRESH_TOKEN)

        // DB에 저장된 expiresAt 기준으로 현재 남은 기간 계산하여 TTL로 설정
        val remainingTTL = Duration.between(LocalDateTime.now(), storedRefreshToken.expiresAt)
        if (remainingTTL.isZero || remainingTTL.isNegative) {
            throw BusinessException(AuthException.EXPIRED_REFRESH_TOKEN)
        }

        refreshTokenRedisRepository.save(userId, storedRefreshToken.tokenHash, remainingTTL)

        return storedRefreshToken.tokenHash
    }

    // 로그아웃
    // TODO: DB/Redis 삭제를 코루틴으로 병렬 처리 (추후 별도 작업으로 적용 예정)
    @Transactional
    fun logout(userId: Long) {
        refreshTokenRepository.deleteByUserId(userId)
        refreshTokenRedisRepository.deleteByUserId(userId)

        log.info(
            "[AUTH] 로그아웃 처리 완료 userId: {}",
            userId,
        )
    }
}
