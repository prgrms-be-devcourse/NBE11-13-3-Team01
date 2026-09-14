package com.example.delivery_project.exception

import org.springframework.http.HttpStatus

enum class DeliveryException(
    override val status: HttpStatus,
    override val code: String,
    override val message: String,
) : ErrorCode {
    DELIVERY_PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "DELIVERY_PLAN_NOT_FOUND", "해당 배송 계획을 찾을 수 없습니다"),
    DELIVERY_STOP_NOT_FOUND(HttpStatus.NOT_FOUND, "DELIVERY_STOP_NOT_FOUND", "해당 배송 목표지를 찾을 수 없습니다"),
    DELIVERY_PLAN_NOT_READY_TO_START(HttpStatus.BAD_REQUEST, "DELIVERY_PLAN_NOT_READY_TO_START", "배송 시작을 할 수 없는 상태입니다"),
    DELIVERY_INVALID_PLAN_STATUS_CHANGE(HttpStatus.BAD_REQUEST, "DELIVERY_INVALID_PLAN_STATUS_CHANGE", "배송 상태를 변경할 수 없는 상태입니다"),
    DELIVERY_INCOMPLETE_STOP(HttpStatus.BAD_REQUEST, "DELIVERY_INCOMPLETE_STOP", "배송 완료로 전환할 수 없습니다"),
    DELIVERY_RECOMMENDATION_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "DELIVERY_RECOMMENDATION_NOT_AVAILABLE", "배송 중인 계획에서만 다음 배송지를 추천할 수 있습니다"),
    DELIVERY_DRIVER_NOT_FOUND(HttpStatus.NOT_FOUND, "DELIVERY_DRIVER_NOT_FOUND", "배송 기사를 찾을 수 없습니다"),
    DRIVER_LOCATION_NOT_FOUND(HttpStatus.NOT_FOUND, "DRIVER_LOCATION_NOT_FOUND", "배송 기사의 위치 정보가 없습니다"),
}
