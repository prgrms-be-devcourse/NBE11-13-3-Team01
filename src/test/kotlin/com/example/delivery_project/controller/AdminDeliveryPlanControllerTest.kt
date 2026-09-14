package com.example.delivery_project.controller

import com.example.delivery_project.dto.request.CreateDeliveryItemRequest
import com.example.delivery_project.dto.request.CreateDeliveryPlanRequest
import com.example.delivery_project.dto.request.CreateDeliveryStopRequest
import com.example.delivery_project.dto.response.AdminDeliveryPlanDetailResponse
import com.example.delivery_project.dto.response.AdminDeliveryPlanSummaryResponse
import com.example.delivery_project.dto.response.AdminDeliveryStatisticsResponse
import com.example.delivery_project.enums.ProductType
import com.example.delivery_project.service.AdminDeliveryPlanService
import com.example.delivery_project.service.DeliveryPlanCreationFacade
import com.example.delivery_project.service.DriverRecommendationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import java.time.LocalDateTime

class AdminDeliveryPlanControllerTest {
    private val adminDeliveryPlanService = mock<AdminDeliveryPlanService>()
    private val deliveryPlanCreationFacade = mock<DeliveryPlanCreationFacade>()
    private val driverRecommendationService = mock<DriverRecommendationService>()
    private val controller = AdminDeliveryPlanController(
        adminDeliveryPlanService,
        deliveryPlanCreationFacade,
        driverRecommendationService,
    )

    @Test
    fun `관리자는 전체 배송계획과 상세를 조회한다`() {
        val summary = mock<AdminDeliveryPlanSummaryResponse>()
        val detail = mock<AdminDeliveryPlanDetailResponse>()
        val statistics = mock<AdminDeliveryStatisticsResponse>()
        whenever(adminDeliveryPlanService.getAllDeliveryPlans()).thenReturn(listOf(summary))
        whenever(adminDeliveryPlanService.getDeliveryPlan(10L)).thenReturn(detail)
        whenever(adminDeliveryPlanService.getDeliveryStatistics()).thenReturn(statistics)

        assertThat(controller.getAllDeliveryPlans()).containsExactly(summary)
        assertThat(controller.getDeliveryPlan(10L)).isSameAs(detail)
        assertThat(controller.getDeliveryStatistics()).isSameAs(statistics)
    }

    @Test
    fun `관리자는 특정 기사에게 배송계획을 생성해 할당한다`() {
        val request = CreateDeliveryPlanRequest(
            "서울 물류센터",
            LocalDateTime.now().plusHours(1),
            listOf(
                CreateDeliveryStopRequest(
                    "서울시청",
                    listOf(CreateDeliveryItemRequest("냉동식품", ProductType.FROZEN, 1)),
                )
            ),
        )
        whenever(deliveryPlanCreationFacade.create(7L, request)).thenReturn(100L)

        val response = controller.create(7L, request)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(response.headers.location).hasToString("/api/admin/delivery-plans/100")
        assertThat(requireNotNull(response.body).planId).isEqualTo(100L)
        verify(deliveryPlanCreationFacade).create(7L, request)
    }
}
