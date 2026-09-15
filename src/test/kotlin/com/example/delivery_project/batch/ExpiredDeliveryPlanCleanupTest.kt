package com.example.delivery_project.batch

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.ProductType
import com.example.delivery_project.enums.Role
import com.example.delivery_project.spec.Location
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import java.time.LocalDateTime

@DataJpaTest(properties = ["spring.sql.init.mode=never"])
@ActiveProfiles("db-test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ExpiredDeliveryPlanCleanupTest {

    @Autowired private lateinit var deliveryPlanRepository: DeliveryPlanRepository
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var entityManager: EntityManager

    @Test
    fun `배송 계획을 지우면 연관 데이터가 함께 사라진다`() {
        val plan = persistCompletedPlan(completedDaysAgo = 40)
        val planId = requireNotNull(plan.id)
        val stopIds = plan.deliveryStops.map { requireNotNull(it.id) }
        val itemIds = plan.deliveryStops.flatMap { it.deliveryItems }.map { requireNotNull(it.id) }
        val assessmentIds = plan.deliveryStops.map { requireNotNull(it.riskAssessment.id) }

        assertThat(stopIds).isNotEmpty()
        assertThat(itemIds).isNotEmpty()

        deliveryPlanRepository.deleteAll(listOf(plan))
        deliveryPlanRepository.flush()
        entityManager.clear() // 1차 캐시를 비워야 실제 DB 상태를 확인할 수 있다.

        assertThat(countById("DeliveryPlan", planId)).isZero()
        assertThat(countByIds("DeliveryStop", stopIds)).isZero()
        assertThat(countByIds("DeliveryItem", itemIds)).isZero()
        assertThat(countByIds("RiskAssessment", assessmentIds)).isZero()
    }

    @Test
    fun `보관 기간이 지난 계획만 조회된다`() {
        val expired = persistCompletedPlan(completedDaysAgo = 31)
        val recent = persistCompletedPlan(completedDaysAgo = 29)
        entityManager.flush()
        entityManager.clear()

        val cutoff = LocalDateTime.now().minusDays(30)
        val found = deliveryPlanRepository.findExpiredPlans(
            status = DeliveryPlanStatus.COMPLETED,
            cutoff = cutoff,
            lastId = 0,
            pageable = PageRequest.of(0, 100),
        ).map { it.id }

        assertThat(found).contains(expired.id)
        assertThat(found).doesNotContain(recent.id)
    }

    @Test
    fun `lastId 다음부터 조회해 같은 행을 두 번 읽지 않는다`() {
        val first = persistCompletedPlan(completedDaysAgo = 40)
        val second = persistCompletedPlan(completedDaysAgo = 40)
        entityManager.flush()
        entityManager.clear()

        val cutoff = LocalDateTime.now().minusDays(30)
        val afterFirst = deliveryPlanRepository.findExpiredPlans(
            status = DeliveryPlanStatus.COMPLETED,
            cutoff = cutoff,
            lastId = requireNotNull(first.id),
            pageable = PageRequest.of(0, 100),
        ).map { it.id }

        assertThat(afterFirst).doesNotContain(first.id)
        assertThat(afterFirst).contains(second.id)
    }

    private fun persistCompletedPlan(completedDaysAgo: Long): DeliveryPlan {
        val driver = userRepository.save(
            User.of("cleanup-${System.nanoTime()}", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER),
        )
        val plan = DeliveryPlanFactory.create(
            driver,
            Location("서울 물류센터", 37.50, 126.90),
            LocalDateTime.now().plusHours(1),
        )
        val stop = plan.addStop("배송지", 37.51, 126.91, LocalDateTime.now())
        stop.addItem("상품", ProductType.NORMAL, 1)
        val saved = deliveryPlanRepository.saveAndFlush(plan)

        // 완료 상태와 완료 시각은 도메인 규칙상 직접 만들 수 없어 리플렉션으로 세팅한다.
        ReflectionTestUtils.setField(saved, "status", DeliveryPlanStatus.COMPLETED)
        ReflectionTestUtils.setField(saved, "completedAt", LocalDateTime.now().minusDays(completedDaysAgo))
        return deliveryPlanRepository.saveAndFlush(saved)
    }

    private fun countById(entity: String, id: Long): Long =
        entityManager.createQuery("SELECT COUNT(e) FROM $entity e WHERE e.id = :id", java.lang.Long::class.java)
            .setParameter("id", id).singleResult.toLong()

    private fun countByIds(entity: String, ids: List<Long>): Long {
        if (ids.isEmpty()) return 0
        return entityManager.createQuery("SELECT COUNT(e) FROM $entity e WHERE e.id IN :ids", java.lang.Long::class.java)
            .setParameter("ids", ids).singleResult.toLong()
    }
}
