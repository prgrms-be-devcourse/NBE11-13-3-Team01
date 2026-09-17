package com.example.delivery_project.exception.global

import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.ExceptionCode
import org.slf4j.LoggerFactory
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(exception: BusinessException): ResponseEntity<ErrorResponse> {
        val errorCode = exception.errorCode
        val response = ErrorResponse.of(errorCode, exception.reason)

        log.warn(
            "[BUSINESS] 비즈니스 예외 발생 code: {}, message: {}, reason: {}",
            errorCode.code,
            errorCode.message,
            exception.reason,
        )

        return ResponseEntity.status(errorCode.status).body(response)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(exception: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val response = ErrorResponse.of(ExceptionCode.INVALID_INPUT, exception.bindingResult)
        log.warn(
            "[VALIDATION] 요청 검증 실패 errorCount: {}",
            exception.bindingResult.errorCount,
        )

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
    }

    @ExceptionHandler(PessimisticLockingFailureException::class)
    fun handleLockConflict(exception: PessimisticLockingFailureException): ResponseEntity<ErrorResponse> {
        val errorCode = DeliveryException.DELIVERY_CLAIM_LOCK_CONFLICT
        log.warn("[LOCK] 행 잠금 경합으로 요청 실패 type: {}", exception.javaClass.simpleName, exception)
        return ResponseEntity.status(errorCode.status).body(ErrorResponse.of(errorCode))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(exception: Exception): ResponseEntity<ErrorResponse> {
        val response = ErrorResponse.of(
            ExceptionCode.UNEXPECTED_ERROR,
            ExceptionCode.UNEXPECTED_ERROR.message,
        )
        log.error("[UNEXPECTED] 예상하지 못한 예외 발생", exception)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response)
    }

    companion object {
        private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
