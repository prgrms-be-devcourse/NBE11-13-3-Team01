package com.example.delivery_project.exception.global

import com.example.delivery_project.exception.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.validation.BindingResult

data class ErrorResponse(
    val status: HttpStatus,
    val code: String,
    val message: String,
    val errors: List<FieldError> = emptyList(),
    val reason: String? = null,
) {
    data class FieldError(
        val field: String,
        val value: String,
        val reason: String?,
    )

    companion object {
        fun of(code: ErrorCode): ErrorResponse = ErrorResponse(
            status = code.status,
            code = code.code,
            message = code.message,
        )

        fun of(code: ErrorCode, reason: String?): ErrorResponse = ErrorResponse(
            status = code.status,
            code = code.code,
            message = code.message,
            reason = reason,
        )

        fun of(code: ErrorCode, bindingResult: BindingResult): ErrorResponse = ErrorResponse(
            status = code.status,
            code = code.code,
            message = code.message,
            errors = bindingResult.fieldErrors.map { error ->
                FieldError(
                    field = error.field,
                    value = error.rejectedValue?.toString().orEmpty(),
                    reason = error.defaultMessage,
                )
            },
        )
    }
}
