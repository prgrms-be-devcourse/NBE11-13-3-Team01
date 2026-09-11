package com.example.delivery_project.exception

import org.springframework.http.HttpStatus

enum class RiskException(
    override val status: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    RISK_NOT_FOUND(HttpStatus.NOT_FOUND, "RISK_NOT_FOUND", "위험도가 존재하지 않습니다"),
    RISK_ARGUMENT_NOT_IMPLEMENTED(HttpStatus.BAD_REQUEST, "RISK_ARGUMENT_NOT_IMPLEMENTED", "필수 항목이 누락되었습니다."),
}
