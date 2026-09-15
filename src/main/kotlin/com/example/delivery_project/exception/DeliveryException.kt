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
    DELIVERY_PLAN_ALREADY_CLAIMED(HttpStatus.CONFLICT, "DELIVERY_PLAN_ALREADY_CLAIMED", "이미 다른 배송 기사가 수령한 배송 업무입니다"),
    DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "DELIVERY_PLAN_CLAIM_LIMIT_EXCEEDED", "동시에 보유할 수 있는 배송 업무 수를 초과했습니다"),
    DELIVERY_PLAN_NOT_RELEASABLE(HttpStatus.BAD_REQUEST, "DELIVERY_PLAN_NOT_RELEASABLE", "배송을 시작하기 전에만 배송 업무를 반납할 수 있습니다"),
    DELIVERY_PLAN_NOT_ASSIGNABLE(HttpStatus.BAD_REQUEST, "DELIVERY_PLAN_NOT_ASSIGNABLE", "배정 전인 배송 업무에만 기사를 추천할 수 있습니다"),
    DELIVERY_CLAIM_LOCK_CONFLICT(HttpStatus.CONFLICT, "DELIVERY_CLAIM_LOCK_CONFLICT", "다른 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요"),
    DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE(HttpStatus.CONFLICT, "DELIVERY_PLAN_PRIORITY_WINDOW_ACTIVE", "아직 추천 기사 우선 수령 시간입니다. 공개 시각 이후에 다시 시도해 주세요"),
}
