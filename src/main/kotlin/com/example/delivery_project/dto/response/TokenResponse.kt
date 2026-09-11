package com.example.delivery_project.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "액세스 토큰 응답")
data class TokenResponse(
    @field:Schema(description = "액세스 토큰")
    val accessToken: String,
)
