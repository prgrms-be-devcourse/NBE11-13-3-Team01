package com.example.delivery_project.controller

import com.example.delivery_project.dto.request.UserJoinRequest
import com.example.delivery_project.dto.request.UserLoginRequest
import com.example.delivery_project.dto.response.UserInfoResponse
import com.example.delivery_project.dto.response.UserLoginResponse
import com.example.delivery_project.security.auth.CustomUserDetails
import com.example.delivery_project.security.jwt.JwtProperties
import com.example.delivery_project.service.TokenService
import com.example.delivery_project.service.UserService
import com.example.delivery_project.util.CookieUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
@Tag(name = "사용자", description = "회원가입, 로그인 및 사용자 정보 관리 API")
class UserController(
    private val userService: UserService,
    private val tokenService: TokenService,
    private val jwtProperties: JwtProperties,
) {
    @Operation(summary = "배송 기사 회원가입", description = "일반 회원가입으로 DELIVERY_DRIVER 권한의 사용자를 생성합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "회원가입 성공"),
        ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음 또는 중복 아이디"),
    ])
    @PostMapping("/join")
    fun join(@Valid @RequestBody request: UserJoinRequest) {
        userService.join(request)
    }

    @Operation(summary = "로그인", description = "Access Token은 응답 본문으로, Refresh Token은 HttpOnly 쿠키로 발급합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "로그인 성공"),
        ApiResponse(responseCode = "400", description = "요청 값이 올바르지 않음"),
        ApiResponse(responseCode = "401", description = "아이디 또는 비밀번호가 올바르지 않음"),
    ])
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: UserLoginRequest,
        @Parameter(hidden = true) response: HttpServletResponse,
    ): UserLoginResponse {
        val tokenPair = userService.login(request)
        CookieUtil.addCookie(
            response,
            CookieUtil.REFRESH_TOKEN_COOKIE,
            tokenPair.refreshToken,
            jwtProperties.refreshTokenValidity.toSeconds().toInt(),
        )
        return UserLoginResponse(tokenPair.accessToken)
    }

    @Operation(summary = "로그아웃", description = "저장된 Refresh Token을 폐기하고 Refresh Token 쿠키를 삭제합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "로그아웃 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
    ])
    @PostMapping("/logout")
    fun logout(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Parameter(hidden = true) request: HttpServletRequest,
        @Parameter(hidden = true) response: HttpServletResponse,
    ) {
        tokenService.logout(requireNotNull(userDetails.user.id))
        CookieUtil.deleteCookie(request, response, CookieUtil.REFRESH_TOKEN_COOKIE)
    }

    @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자의 식별 정보와 권한을 조회합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "사용자 정보 조회 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
    ])
    @GetMapping("/info")
    fun getMyInfo(
        @Parameter(hidden = true) @AuthenticationPrincipal userDetails: CustomUserDetails,
    ): UserInfoResponse {
        val user = userDetails.user
        return UserInfoResponse(requireNotNull(user.id), user.loginId, user.name, user.role)
    }

    @Operation(summary = "회원 탈퇴", description = "현재 로그인한 사용자를 탈퇴 처리(soft delete)하고, 저장된 Refresh Token과 쿠키를 삭제합니다.")
    @ApiResponses(value = [
        ApiResponse(responseCode = "200", description = "회원 탈퇴 성공"),
        ApiResponse(responseCode = "401", description = "인증 필요"),
    ])
    @DeleteMapping("/me")
    fun withdraw(
        @Parameter(hidden = true)
        @AuthenticationPrincipal
        userDetails: CustomUserDetails,

        @Parameter(hidden = true)
        request: HttpServletRequest,

        @Parameter(hidden = true)
        response: HttpServletResponse,
    ) {
        // 현재 로그인 중인 회원 탈퇴 처리
        userService.withdraw(requireNotNull(userDetails.user.id))

        // 쿠키에 있는 Refresh Token 삭제
        CookieUtil.deleteCookie(request, response, CookieUtil.REFRESH_TOKEN_COOKIE)
    }
}
