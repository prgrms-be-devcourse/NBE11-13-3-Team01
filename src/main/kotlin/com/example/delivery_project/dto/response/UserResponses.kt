package com.example.delivery_project.dto.response

import com.example.delivery_project.enums.Role
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "액세스 토큰 응답")
data class TokenResponse(
    @field:Schema(description = "액세스 토큰")
    val accessToken: String,
)

@Schema(description = "유저 정보 응답")
data class UserInfoResponse(
    @field:Schema(description = "유저 id", example = "1")
    val id: Long?,
    @field:Schema(description = "로그인 id", example = "driver1234")
    val loginId: String,
    @field:Schema(description = "유저 이름", example = "홍길동")
    val name: String,
    @field:Schema(description = "유저 권한", example = "ROLE_DELIVERY_DRIVER")
    val role: Role,
)

@Schema(description = "유저 로그인 응답")
data class UserLoginResponse(
    @field:Schema(description = "액세스 토큰")
    val accessToken: String,
)
