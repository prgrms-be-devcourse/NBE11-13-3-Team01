package com.example.delivery_project.domain.entity

import com.example.delivery_project.domain.entity.delivery.DeliveryItem
import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryStop
import com.example.delivery_project.domain.entity.delivery.RiskAssessment
import com.example.delivery_project.domain.entity.delivery.RiskFactor
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.entity.weather.Weather
import jakarta.persistence.Column
import jakarta.persistence.OrderBy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

class JpaEntityMappingTest {

    @Test
    fun `지연 로딩 프록시가 엔티티와 getter를 재정의할 수 있다`() {
        val proxyTargets = mapOf(
            User::class.java to "getLoginId",
            DeliveryItem::class.java to "getDeliveryStop",
            DeliveryStop::class.java to "getAddress",
            RiskAssessment::class.java to "getAnalyzedAt",
            RiskFactor::class.java to "getDescription",
            Weather::class.java to "getBaseDate",
            DeliveryPlan::class.java to "getActualDepartureAt",
        )

        proxyTargets.forEach { (entityType, getterName) ->
            assertThat(Modifier.isFinal(entityType.modifiers)).isFalse()
            assertThat(Modifier.isFinal(entityType.getMethod(getterName).modifiers)).isFalse()
        }
    }

    @Test
    fun `배송 순서는 자식 엔티티의 sequence 속성으로 정렬한다`() {
        val stopsField = DeliveryPlan::class.java.getDeclaredField("deliveryStopEntities")
        val sequenceField = DeliveryStop::class.java.getDeclaredField("sequence")

        assertThat(stopsField.getAnnotation(OrderBy::class.java).value).isEqualTo("sequence ASC")
        assertThat(sequenceField.getAnnotation(Column::class.java).name).isEqualTo("sequence")
    }
}
