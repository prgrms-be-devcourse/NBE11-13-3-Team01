package com.example.delivery_project.exception

import org.springframework.http.HttpStatus

enum class AuthException(
    override val status: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    REFRESH_TOKEN_NOT_FOUND(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_NOT_FOUND", "Refresh Token을 찾을 수 없습니다"),
    EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "EXPIRED_REFRESH_TOKEN", "Refresh Token이 만료되었습니다"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "유효하지 않은 Refresh Token입니다"),
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "인증이 필요합니다"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다"),
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "DUPLICATE_LOGIN_ID", "이미 사용 중인 아이디입니다"),
    INVALID_LOGIN(HttpStatus.UNAUTHORIZED, "INVALID_LOGIN", "아이디 또는 비밀번호가 올바르지 않습니다"),
    WITHDRAW_BLOCKED_BY_ACTIVE_DELIVERY(
        HttpStatus.CONFLICT,
        "WITHDRAW_BLOCKED_BY_ACTIVE_DELIVERY",
        "진행 중인 배송 업무가 있어 탈퇴할 수 없습니다. 먼저 반납하거나 완료해 주세요",
    ),
}
