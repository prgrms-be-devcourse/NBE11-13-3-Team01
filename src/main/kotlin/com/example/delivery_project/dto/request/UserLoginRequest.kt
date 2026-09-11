package com.example.delivery_project.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "유저 로그인 요청")
data class UserLoginRequest(
    @field:Schema(description = "로그인 아이디", example = "driver1234")
    @field:NotBlank(message = "아이디는 필수입니다.")
    val loginId: String = "",
    @field:Schema(description = "비밀번호", example = "pw123")
    @field:NotBlank(message = "비밀번호는 필수입니다.")
    val password: String = "",
)
