package com.example.delivery_project.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "유저 로그인 응답")
data class UserLoginResponse(
    @field:Schema(description = "액세스 토큰")
    val accessToken: String,
)
