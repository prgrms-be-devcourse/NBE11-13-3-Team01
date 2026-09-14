package com.example.delivery_project.dto.request

import com.example.delivery_project.enums.ProductType
import jakarta.validation.Validation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class RequestDtoTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `배송계획과 배송지의 null 목록을 빈 목록으로 바꾼다`() {
        val stop = CreateDeliveryStopRequest("배송지", null)
        val plan = CreateDeliveryPlanRequest("물류센터", LocalDateTime.now(), null)

        assertThat(stop.items).isEmpty()
        assertThat(plan.stops).isEmpty()
    }

    @Test
    fun `전달받은 목록을 복사해 외부 변경으로부터 보호한다`() {
        val stops = mutableListOf(CreateDeliveryStopRequest("배송지", emptyList()))
        val request = CreateDeliveryPlanRequest("물류센터", LocalDateTime.now(), stops)

        stops.clear()

        assertThat(request.stops).hasSize(1)
    }

    @Test
    fun `배송계획 생성요청의 중첩된 필수값을 검증한다`() {
        val request = CreateDeliveryPlanRequest(
            " ",
            null,
            listOf(
                CreateDeliveryStopRequest(
                    " ",
                    listOf(CreateDeliveryItemRequest(" ", null, 0)),
                ),
            ),
        )

        val propertyPaths = validator.validate(request).map { it.propertyPath.toString() }

        assertThat(propertyPaths).contains(
            "departureAddress",
            "scheduledDepartureAt",
            "stops[0].address",
            "stops[0].items[0].productName",
            "stops[0].items[0].productType",
            "stops[0].items[0].quantity",
        )
    }

    @Test
    fun `정상적인 배송계획 생성요청은 검증을 통과한다`() {
        val request = CreateDeliveryPlanRequest(
            "서울 물류센터",
            LocalDateTime.now().plusHours(1),
            listOf(
                CreateDeliveryStopRequest(
                    "서울시청",
                    listOf(CreateDeliveryItemRequest("냉동식품", ProductType.FROZEN, 1)),
                ),
            ),
        )

        assertThat(validator.validate(request)).isEmpty()
    }

    @Test
    fun `기사 위치는 위도와 경도의 유효 범위를 검증한다`() {
        val request = UpdateDriverLocationRequest(90.1, -180.1)

        assertThat(validator.validate(request).map { it.propertyPath.toString() })
            .containsExactlyInAnyOrder("latitude", "longitude")
        assertThat(validator.validate(UpdateDriverLocationRequest(37.5665, 126.9780))).isEmpty()
    }
}
