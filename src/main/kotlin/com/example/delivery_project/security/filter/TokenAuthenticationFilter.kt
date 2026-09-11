package com.example.delivery_project.security.filter

import com.example.delivery_project.security.jwt.TokenProvider
import com.example.delivery_project.security.jwt.TokenStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class TokenAuthenticationFilter(private val tokenProvider: TokenProvider) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)
        if (token != null && tokenProvider.validateToken(token) == TokenStatus.VALID) {
            val user = tokenProvider.getTokenDetails(token)
            val authentication = tokenProvider.getAuthentication(user, token)
            SecurityContextHolder.getContext().authentication = authentication
            log.debug("Authentication success. userId: {}, uri: {}", user.id, request.requestURI)
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? =
        request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith("Bearer ") }
            ?.substring(7)

    private companion object {
        val log = LoggerFactory.getLogger(TokenAuthenticationFilter::class.java)
    }
}
