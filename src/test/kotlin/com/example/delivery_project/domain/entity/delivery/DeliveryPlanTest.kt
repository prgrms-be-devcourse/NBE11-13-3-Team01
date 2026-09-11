package com.example.delivery_project.domain.entity.delivery

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.DeliveryStopStatus
import com.example.delivery_project.enums.ProductType
import com.example.delivery_project.enums.RiskFactorType
import com.example.delivery_project.enums.RiskLevel
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.DeliveryException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.spec.DeliveryItemSpec
import com.example.delivery_project.spec.DeliveryStopSpec
import com.example.delivery_project.spec.Location
import com.example.delivery_project.spec.RiskFactorSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime
import kotlin.test.assertFailsWith

class DeliveryPlanTest {
    private val departure = Location("서울 물류센터", 37.5665, 126.9780)
    private val driver = User.of(
        1L,
        "driver",
        "encoded-password",
        "배송기사",
        Role.ROLE_DELIVERY_DRIVER,
    )

    @Test
    fun `팩토리는 배송지 상품 위험요인을 함께 생성한다`() {
        val plan = DeliveryPlanFactory.create(
            driver,
            departure,
            LocalDateTime.now().plusHours(1),
            listOf(
                DeliveryStopSpec(
                    Location("서울시청", 37.5663, 126.9779),
                    listOf(DeliveryItemSpec("냉동식품", ProductType.FROZEN, 2)),
                    listOf(
                        RiskFactorSpec(RiskFactorType.HEAVY_RAIN, "폭우"),
                        RiskFactorSpec(RiskFactorType.WEATHER_WARNING, "기상 특보"),
                    ),
                ),
            ),
        )

        val stop = plan.deliveryStops.first()
        val item = stop.deliveryItems.first()

        assertThat(plan.status).isEqualTo(DeliveryPlanStatus.READY)
        assertThat(plan.totalStops).isEqualTo(1)
        assertThat(stop.status).isEqualTo(DeliveryStopStatus.READY)
        assertThat(item.productName).isEqualTo("냉동식품")
        assertThat(item.productType).isEqualTo(ProductType.FROZEN)
        assertThat(item.quantity).isEqualTo(2)
        assertThat(stop.riskAssessment.score).isEqualTo(70)
        assertThat(stop.riskAssessment.level).isEqualTo(RiskLevel.DANGER)
        assertThat(plan.dangerStops).isEqualTo(1)
    }

    @Test
    fun `배송지가 없는 계획은 시작할 수 없다`() {
        assertBusinessException(emptyPlan()::start, DeliveryException.DELIVERY_PLAN_NOT_READY_TO_START)
    }

    @Test
    fun `배송을 시작하면 계획과 모든 배송지가 DELIVERING이 된다`() {
        val plan = planWithTwoStops()

        plan.start()

        assertThat(plan.status).isEqualTo(DeliveryPlanStatus.DELIVERING)
        assertThat(plan.actualDepartureAt).isNotNull()
        assertThat(plan.deliveryStops).allMatch { it.status == DeliveryStopStatus.DELIVERING }
    }

    @Test
    fun `READY 상태에서만 배송 순서를 변경할 수 있다`() {
        val plan = planWithTwoStops()
        setId(plan.deliveryStops[0], 1L)
        setId(plan.deliveryStops[1], 2L)

        plan.reorderStops(listOf(2L, 1L))

        assertThat(plan.deliveryStops.map { it.id }).containsExactly(2L, 1L)
        assertThat(plan.deliveryStops.map { it.sequence }).containsExactly(0, 1)

        plan.start()
        assertBusinessException(
            { plan.reorderStops(listOf(1L, 2L)) },
            DeliveryException.DELIVERY_INVALID_PLAN_STATUS_CHANGE,
        )
    }

    @Test
    fun `일부 배송지를 누락한 순서 변경은 거부한다`() {
        val plan = planWithTwoStops()
        setId(plan.deliveryStops[0], 1L)
        setId(plan.deliveryStops[1], 2L)

        assertBusinessException(
            { plan.reorderStops(listOf(1L)) },
            DeliveryException.DELIVERY_INVALID_PLAN_STATUS_CHANGE,
        )
    }

    @Test
    fun `모든 배송지를 완료해야 계획을 완료할 수 있다`() {
        val plan = planWithTwoStops()
        setId(plan.deliveryStops[0], 1L)
        setId(plan.deliveryStops[1], 2L)
        plan.start()

        plan.completeStop(1L)

        assertThat(plan.remainingStops).isEqualTo(1)
        assertBusinessException(plan::finish, DeliveryException.DELIVERY_INCOMPLETE_STOP)

        plan.completeStop(2L)
        plan.finish()

        assertThat(plan.status).isEqualTo(DeliveryPlanStatus.COMPLETED)
        assertThat(plan.remainingStops).isZero()
        assertThat(plan.completedAt).isNotNull()
        assertThat(plan.isFinished).isTrue()
    }

    @Test
    fun `배송 시작 후에는 예정시각과 상품을 변경할 수 없다`() {
        val plan = planWithTwoStops()
        val stop = plan.deliveryStops.first()
        plan.start()

        assertBusinessException(
            { plan.updateScheduledDepartureAt(LocalDateTime.now().plusDays(1)) },
            DeliveryException.DELIVERY_INVALID_PLAN_STATUS_CHANGE,
        )
        assertBusinessException(
            { stop.addItem("추가 상품", ProductType.NORMAL, 1) },
            DeliveryException.DELIVERY_INVALID_PLAN_STATUS_CHANGE,
        )
    }

    @Test
    fun `계획에 속하지 않은 배송지는 완료할 수 없다`() {
        val plan = planWithTwoStops()
        setId(plan.deliveryStops[0], 1L)
        setId(plan.deliveryStops[1], 2L)
        plan.start()

        assertBusinessException({ plan.completeStop(999L) }, DeliveryException.DELIVERY_STOP_NOT_FOUND)
    }

    private fun emptyPlan(): DeliveryPlan = DeliveryPlanFactory.create(
        driver,
        departure,
        LocalDateTime.now().plusHours(1),
    )

    private fun planWithTwoStops(): DeliveryPlan = emptyPlan().apply {
        val analyzedAt = LocalDateTime.now()
        addStop("배송지 1", 37.57, 126.98, analyzedAt)
        addStop("배송지 2", 37.58, 126.99, analyzedAt)
    }

    private fun setId(target: Any, id: Long) {
        ReflectionTestUtils.setField(target, "id", id)
    }

    private fun assertBusinessException(action: () -> Unit, expected: DeliveryException) {
        val exception = assertFailsWith<BusinessException>(block = action)
        assertThat(exception.errorCode).isEqualTo(expected)
    }
}
