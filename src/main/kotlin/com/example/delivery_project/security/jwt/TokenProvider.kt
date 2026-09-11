package com.example.delivery_project.security.jwt

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.enums.Role
import com.example.delivery_project.security.auth.CustomUserDetails
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.JwtParser
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.Base64
import java.util.Date
import javax.crypto.SecretKey

@Service
class TokenProvider(private val jwtProperties: JwtProperties) {
    private lateinit var secretKey: SecretKey
    private lateinit var jwtParser: JwtParser

    @PostConstruct
    private fun init() {
        secretKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(jwtProperties.secretKey))
        jwtParser = Jwts.parser()
            .verifyWith(secretKey)
            .requireIssuer(jwtProperties.issuer)
            .build()
    }

    fun generateToken(user: User, validity: Duration): String {
        val now = Date()
        val expiredAt = Date(now.time + validity.toMillis())

        return Jwts.builder()
            .header().type("JWT").and()
            .issuer(jwtProperties.issuer)
            .issuedAt(now)
            .expiration(expiredAt)
            .subject(user.loginId)
            .claim(CLAIM_ID, user.id)
            .claim(CLAIM_NAME, user.name)
            .claim(CLAIM_ROLE, user.role.name)
            .signWith(secretKey, Jwts.SIG.HS512)
            .compact()
    }

    fun validateToken(token: String?): TokenStatus = try {
        jwtParser.parseSignedClaims(token)
        log.debug("Token is valid")
        TokenStatus.VALID
    } catch (_: ExpiredJwtException) {
        log.warn("Token is expired")
        TokenStatus.EXPIRED
    } catch (_: JwtException) {
        log.warn("Token is invalid")
        TokenStatus.INVALID
    } catch (_: IllegalArgumentException) {
        log.warn("Token is invalid")
        TokenStatus.INVALID
    }

    fun getTokenDetails(token: String): User {
        val claims = jwtParser.parseSignedClaims(token).payload
        return User(
            id = claims.get(CLAIM_ID, Long::class.javaObjectType),
            loginId = claims.subject,
            password = null,
            name = claims.get(CLAIM_NAME, String::class.java),
            role = Role.valueOf(claims.get(CLAIM_ROLE, String::class.java)),
        )
    }

    fun getAuthentication(user: User, token: String): Authentication {
        val principal = CustomUserDetails(user)
        return UsernamePasswordAuthenticationToken(principal, token, principal.getAuthorities())
    }

    private companion object {
        const val CLAIM_ID = "id"
        const val CLAIM_NAME = "name"
        const val CLAIM_ROLE = "role"
        val log = LoggerFactory.getLogger(TokenProvider::class.java)
    }
}
