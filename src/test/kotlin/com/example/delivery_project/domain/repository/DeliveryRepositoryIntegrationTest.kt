package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.delivery.DeliveryPlanFactory
import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.entity.weather.Weather
import com.example.delivery_project.dto.response.DeliveryPlanDetailResponse
import com.example.delivery_project.enums.DeliveryStopStatus
import com.example.delivery_project.enums.ProductType
import com.example.delivery_project.enums.RiskFactorType
import com.example.delivery_project.enums.Role
import com.example.delivery_project.spec.Location
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@DataJpaTest(
    properties = [
        "spring.sql.init.mode=never",
        "spring.jpa.properties.hibernate.generate_statistics=true",
    ],
)
@ActiveProfiles("db-test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class DeliveryRepositoryIntegrationTest {
    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var deliveryPlanRepository: DeliveryPlanRepository

    @Autowired
    private lateinit var deliveryStopRepository: DeliveryStopRepository

    @Autowired
    private lateinit var riskAssessmentRepository: RiskAssessmentRepository

    @Autowired
    private lateinit var weatherRepository: WeatherRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Test
    fun `배송계획 연관관계와 활성 배송지 쿼리를 검증한다`() {
        val driver = userRepository.save(
            User.of("repository-driver", "password", "배송기사", Role.ROLE_DELIVERY_DRIVER),
        )
        val plan = DeliveryPlanFactory.create(
            driver,
            Location("서울 물류센터", 37.50, 126.90),
            LocalDateTime.now().plusHours(1),
        )
        val first = plan.addStop("배송지 1", 37.51, 126.91, LocalDateTime.now())
        plan.addStop("배송지 2", 37.52, 126.92, LocalDateTime.now())
        first.addItem("상품", ProductType.NORMAL, 1)
        val savedPlan = deliveryPlanRepository.saveAndFlush(plan)
        val planId = requireNotNull(savedPlan.id)
        val driverId = requireNotNull(driver.id)

        assertThat(deliveryPlanRepository.findByIdAndDriverId(planId, driverId)).isNotNull()
        assertThat(deliveryPlanRepository.findByIdAndDriverId(planId, Long.MAX_VALUE)).isNull()

        val summary = deliveryPlanRepository.findAllSummariesByDriverId(driverId).first()
        assertThat(summary.planId).isEqualTo(planId)
        assertThat(summary.driverId).isEqualTo(driverId)
        assertThat(summary.totalStops.toLong()).isEqualTo(2L)
        assertThat(summary.remainingStops.toLong()).isEqualTo(2L)
        assertThat(summary.totalBoxes.toLong()).isEqualTo(1L)
        assertThat(summary.remainingBoxes.toLong()).isEqualTo(1L)
        assertThat(summary.dangerStops.toLong()).isZero()
        assertThat(deliveryPlanRepository.findAllSummaries().map { it.planId }).contains(planId)

        val readyStops = deliveryStopRepository.findAllWithRiskByStatusIn(listOf(DeliveryStopStatus.READY))
        val planStops = deliveryStopRepository.findAllWithRiskByDeliveryPlanIdAndStatusIn(
            planId,
            listOf(DeliveryStopStatus.READY),
        )

        assertThat(readyStops).hasSize(2)
        assertThat(planStops).hasSize(2)
        val detail = requireNotNull(deliveryStopRepository.findDetailByIdAndPlanId(requireNotNull(first.id), planId))
        assertThat(detail.deliveryItems).hasSize(1)

        savedPlan.start()
        deliveryPlanRepository.flush()

        assertThat(deliveryStopRepository.findAllWithRiskByStatusIn(listOf(DeliveryStopStatus.DELIVERING))).hasSize(2)
    }

    @Test
    fun `날씨 시간범위 조회와 UPSERT용 UPDATE 쿼리를 검증한다`() {
        val date = LocalDate.of(2026, 8, 18)
        val forecastTime = LocalTime.of(11, 0)
        weatherRepository.saveAndFlush(
            Weather.of(60, 127, date, forecastTime, date, LocalTime.of(10, 30), "T1H", "32"),
        )

        assertThat(
            weatherRepository.findByNxAndNyAndFcstDateBetweenAndCategoryIn(
                60,
                127,
                date.minusDays(1),
                date,
                listOf("T1H", "RN1", "PTY"),
            ),
        ).hasSize(1)

        val updated = weatherRepository.updateFcstValue(
            60,
            127,
            date,
            forecastTime,
            date,
            LocalTime.of(10, 30),
            "T1H",
            "33",
            LocalDateTime.now(),
        )
        weatherRepository.flush()
        entityManager.clear()

        assertThat(updated).isEqualTo(1)
        val weather = weatherRepository.findByNxAndNyAndFcstDateAndFcstTimeAndCategoryIn(
            60,
            127,
            date,
            forecastTime,
            listOf("T1H"),
        ).single()
        assertThat(weather.fcstValue).isEqualTo("33")
    }

    @Test
    fun `배송지 수가 늘어도 상세 조회는 세 쿼리로 고정된다`() {
        val driver = userRepository.save(
            User.of("detail-query-driver", "password", "상세조회기사", Role.ROLE_DELIVERY_DRIVER),
        )
        val plan = DeliveryPlanFactory.create(
            driver,
            Location("서울 물류센터", 37.50, 126.90),
            LocalDateTime.now().plusHours(1),
        )
        repeat(10) { index ->
            val stop = plan.addStop(
                "배송지 $index",
                37.51 + index * 0.001,
                126.91 + index * 0.001,
                LocalDateTime.now(),
            )
            stop.addItem("상품 $index", ProductType.NORMAL, index + 1)
            stop.riskAssessment.replaceFactors(listOf(RiskFactorType.HEAVY_RAIN), LocalDateTime.now())
        }
        val savedPlan = deliveryPlanRepository.saveAndFlush(plan)
        val planId = requireNotNull(savedPlan.id)
        entityManager.clear()

        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.clear()

        val loadedPlan = requireNotNull(deliveryPlanRepository.findDetailById(planId))
        deliveryStopRepository.findAllWithItemsByDeliveryPlanId(planId)
        riskAssessmentRepository.findAllWithFactorsByDeliveryPlanId(planId)
        val response = DeliveryPlanDetailResponse.from(loadedPlan)

        assertThat(response.deliveryStops).hasSize(10)
        assertThat(response.deliveryStops).allSatisfy { stop ->
            assertThat(stop.deliveryItems).hasSize(1)
            assertThat(stop.riskAssessment.factors).hasSize(1)
        }
        assertThat(statistics.prepareStatementCount).isEqualTo(3L)
    }
}
