package com.example.delivery_project.exception.global

import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.ExceptionCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.core.MethodParameter
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DeadlockLoserDataAccessException
import org.springframework.validation.BeanPropertyBindingResult
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException

class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()

    @Test
    fun `비즈니스 예외의 상태와 사유를 응답한다`() {
        val response = handler.handleBusinessException(
            BusinessException(DeliveryException.DELIVERY_PLAN_NOT_FOUND, "planId=10"),
        )

        assertThat(response.statusCode).isEqualTo(DeliveryException.DELIVERY_PLAN_NOT_FOUND.status)
        assertThat(response.body?.code).isEqualTo("DELIVERY_PLAN_NOT_FOUND")
        assertThat(response.body?.reason).isEqualTo("planId=10")
    }

    @Test
    fun `예상하지 못한 예외는 내부정보를 숨긴 500으로 응답한다`() {
        val response = handler.handleException(IllegalStateException("민감한 내부 오류"))

        assertThat(response.statusCode).isEqualTo(ExceptionCode.UNEXPECTED_ERROR.status)
        assertThat(response.body?.code).isEqualTo("UNEXPECTED_ERROR")
        assertThat(response.body?.reason).doesNotContain("민감한 내부 오류")
    }

    @Test
    fun `락 대기 타임아웃은 재시도 가능한 409로 응답한다`() {
        val response = handler.handleLockConflict(
            CannotAcquireLockException("Lock wait timeout exceeded"),
        )

        assertThat(response.statusCode.value()).isEqualTo(409)
        assertThat(response.body?.code).isEqualTo("DELIVERY_CLAIM_LOCK_CONFLICT")
        assertThat(response.body?.message).doesNotContain("Lock wait timeout")
    }

    @Test
    fun `데드락 희생자도 409로 응답한다`() {
        val response = handler.handleLockConflict(
            DeadlockLoserDataAccessException("deadlock", RuntimeException("Deadlock found")),
        )

        assertThat(response.statusCode.value()).isEqualTo(409)
        assertThat(response.body?.code).isEqualTo("DELIVERY_CLAIM_LOCK_CONFLICT")
    }

    @Test
    fun `요청값 검증 오류의 필드정보를 400으로 응답한다`() {
        val bindingResult = BeanPropertyBindingResult(Any(), "request").apply {
            addError(
                FieldError(
                    "request",
                    "loginId",
                    "",
                    false,
                    null,
                    null,
                    "아이디는 필수입니다.",
                ),
            )
        }
        val exception = MethodArgumentNotValidException(mock(MethodParameter::class.java), bindingResult)

        val response = handler.handleValidationException(exception)

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body?.code).isEqualTo("INVALID_INPUT")
        val error = requireNotNull(response.body).errors.single()
        assertThat(error.field).isEqualTo("loginId")
        assertThat(error.reason).isEqualTo("아이디는 필수입니다.")
    }
}
