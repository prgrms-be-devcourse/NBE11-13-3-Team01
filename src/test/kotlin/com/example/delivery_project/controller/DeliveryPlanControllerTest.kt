package com.example.delivery_project.controller

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.dto.request.UpdateDeliveryOrderRequest
import com.example.delivery_project.dto.response.DeliveryPlanDetailResponse
import com.example.delivery_project.dto.response.DeliveryPlanSummaryResponse
import com.example.delivery_project.dto.response.DeliveryStopResponse
import com.example.delivery_project.dto.response.NextStopRecommendationResponse
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.Role
import com.example.delivery_project.security.auth.CustomUserDetails
import com.example.delivery_project.service.DeliveryPlanService
import com.example.delivery_project.service.NextStopRecommendationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

class DeliveryPlanControllerTest {
    private val deliveryPlanService = mock<DeliveryPlanService>()
    private val nextStopRecommendationService = mock<NextStopRecommendationService>()
    private val controller = DeliveryPlanController(deliveryPlanService, nextStopRecommendationService)
    private val userDetails = CustomUserDetails(
        User(
            id = 7L,
            loginId = "driver",
            password = "password",
            name = "배송기사",
            role = Role.ROLE_DELIVERY_DRIVER,
        )
    )

    @Test
    fun `로그인 기사의 배송계획 목록을 조회한다`() {
        val summary = DeliveryPlanSummaryResponse(
            10L,
            "서울 물류센터",
            LocalDateTime.now().plusHours(1),
            LocalDateTime.now(),
            null,
            null,
            DeliveryPlanStatus.READY,
            2,
            2,
            4,
            4,
            0,
        )
        whenever(deliveryPlanService.getDeliveryPlans(7L)).thenReturn(listOf(summary))

        val response = controller.getMyDeliveryPlans(userDetails)

        assertThat(response).containsExactly(summary)
        verify(deliveryPlanService).getDeliveryPlans(7L)
    }

    @Test
    fun `배송계획과 배송지 상세조회 요청을 서비스에 전달한다`() {
        val planResponse = mock<DeliveryPlanDetailResponse>()
        val stopResponse = mock<DeliveryStopResponse>()
        whenever(deliveryPlanService.getDeliveryPlan(10L, 7L)).thenReturn(planResponse)
        whenever(deliveryPlanService.getDeliveryStop(10L, 101L, 7L)).thenReturn(stopResponse)

        assertThat(controller.getDeliveryPlan(userDetails, 10L)).isSameAs(planResponse)
        assertThat(controller.getDeliveryStop(userDetails, 10L, 101L)).isSameAs(stopResponse)
    }

    @Test
    fun `다음 배송지 추천 요청을 서비스에 전달한다`() {
        val recommendation = mock<NextStopRecommendationResponse>()
        whenever(nextStopRecommendationService.recommend(10L, 7L)).thenReturn(recommendation)

        assertThat(controller.recommendNextStop(userDetails, 10L)).isSameAs(recommendation)
        verify(nextStopRecommendationService).recommend(10L, 7L)
    }

    @Test
    fun `배송순서를 변경하면 204를 반환한다`() {
        val orderRequest = UpdateDeliveryOrderRequest(listOf(102L, 101L))

        val orderResponse = controller.reorderStops(userDetails, 10L, orderRequest)

        assertThat(orderResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        verify(deliveryPlanService).reorderStops(10L, 7L, orderRequest)
    }

    @Test
    fun `배송 상태변경 요청을 서비스에 전달하고 204를 반환한다`() {
        val startResponse = controller.start(userDetails, 10L)
        val stopResponse = controller.completeStop(userDetails, 10L, 101L)
        val planResponse = controller.completePlan(userDetails, 10L)

        assertThat(startResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(stopResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(planResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        verify(deliveryPlanService).start(10L, 7L)
        verify(deliveryPlanService).completeStop(10L, 101L, 7L)
        verify(deliveryPlanService).completePlan(10L, 7L)
    }
}
