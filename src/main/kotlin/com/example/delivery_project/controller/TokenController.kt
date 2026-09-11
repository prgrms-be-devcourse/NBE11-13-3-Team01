package com.example.delivery_project.controller

import com.example.delivery_project.dto.response.TokenResponse
import com.example.delivery_project.security.jwt.JwtProperties
import com.example.delivery_project.service.TokenService
import com.example.delivery_project.util.CookieUtil
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/tokens")
class TokenController(
    private val tokenService: TokenService,
    private val jwtProperties: JwtProperties,
) {
    @PostMapping("/refresh")
    fun refreshToken(request: HttpServletRequest, response: HttpServletResponse): TokenResponse {
        val tokenPair = tokenService.refreshToken(request.cookies)
        CookieUtil.addCookie(
            response,
            CookieUtil.REFRESH_TOKEN_COOKIE,
            tokenPair.refreshToken,
            jwtProperties.refreshTokenValidity.toSeconds().toInt(),
        )
        return TokenResponse(tokenPair.accessToken)
    }
}
