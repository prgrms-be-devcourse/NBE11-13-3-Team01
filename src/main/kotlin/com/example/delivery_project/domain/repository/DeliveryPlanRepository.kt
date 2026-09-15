package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.dto.projection.DeliveryPlanSummaryProjection
import com.example.delivery_project.enums.DeliveryPlanStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface DeliveryPlanRepository : JpaRepository<DeliveryPlan, Long> {
    @Query(
        """
        select distinct p
        from DeliveryPlan p
        join fetch p.driver
        left join fetch p.deliveryStopEntities s
        left join fetch s.riskAssessment
        where p.id = :id
        """,
    )
    fun findDetailById(id: Long): DeliveryPlan?

    @Query(
        """
        select distinct p
        from DeliveryPlan p
        left join fetch p.deliveryStopEntities s
        left join fetch s.riskAssessment
        where p.id = :planId
          and p.driver.id = :driverId
        """,
    )
    fun findWithStopsAndRiskByIdAndDriverId(planId: Long, driverId: Long): DeliveryPlan?

    @Query(
        value = """
        SELECT
            p.id AS planId,
            u.id AS driverId,
            u.login_id AS driverLoginId,
            u.name AS driverName,
            p.departure_location AS departureLocation,
            p.scheduled_departure_at AS scheduledDepartureAt,
            p.actual_departure_at AS actualDepartureAt,
            p.completed_at AS completedAt,
            p.status AS status,
            COUNT(DISTINCT s.id) AS totalStops,
            COUNT(DISTINCT CASE
                WHEN s.status <> 'COMPLETED' THEN s.id
            END) AS remainingStops,
            COALESCE(SUM(i.quantity), 0) AS totalBoxes,
            COALESCE(SUM(CASE
                WHEN s.status <> 'COMPLETED' THEN i.quantity
                ELSE 0
            END), 0) AS remainingBoxes,
            COUNT(DISTINCT CASE
                WHEN s.status <> 'COMPLETED' AND ra.level = 'DANGER' THEN s.id
            END) AS dangerStops
        FROM delivery_plan p
        JOIN users u ON u.id = p.driver_id
        LEFT JOIN delivery_stop s ON s.delivery_plan_id = p.id
        LEFT JOIN delivery_item i ON i.delivery_stop_id = s.id
        LEFT JOIN risk_assessment ra ON ra.delivery_stop_id = s.id
        WHERE p.driver_id = :driverId
        GROUP BY
            p.id,
            u.id,
            u.login_id,
            u.name,
            p.departure_location,
            p.scheduled_departure_at,
            p.actual_departure_at,
            p.completed_at,
            p.status
        ORDER BY p.scheduled_departure_at ASC
        """,
        nativeQuery = true,
    )
    fun findAllSummariesByDriverId(
        @Param("driverId") driverId: Long,
    ): List<DeliveryPlanSummaryProjection>

    @Query(
        value = """
        SELECT
            p.id AS planId,
            u.id AS driverId,
            u.login_id AS driverLoginId,
            u.name AS driverName,
            p.departure_location AS departureLocation,
            p.scheduled_departure_at AS scheduledDepartureAt,
            p.actual_departure_at AS actualDepartureAt,
            p.completed_at AS completedAt,
            p.status AS status,
            COUNT(DISTINCT s.id) AS totalStops,
            COUNT(DISTINCT CASE
                WHEN s.status <> 'COMPLETED' THEN s.id
            END) AS remainingStops,
            COALESCE(SUM(i.quantity), 0) AS totalBoxes,
            COALESCE(SUM(CASE
                WHEN s.status <> 'COMPLETED' THEN i.quantity
                ELSE 0
            END), 0) AS remainingBoxes,
            COUNT(DISTINCT CASE
                WHEN s.status <> 'COMPLETED' AND ra.level = 'DANGER' THEN s.id
            END) AS dangerStops
        FROM delivery_plan p
        JOIN users u ON u.id = p.driver_id
        LEFT JOIN delivery_stop s ON s.delivery_plan_id = p.id
        LEFT JOIN delivery_item i ON i.delivery_stop_id = s.id
        LEFT JOIN risk_assessment ra ON ra.delivery_stop_id = s.id
        GROUP BY
            p.id,
            u.id,
            u.login_id,
            u.name,
            p.departure_location,
            p.scheduled_departure_at,
            p.actual_departure_at,
            p.completed_at,
            p.status
        ORDER BY p.scheduled_departure_at ASC
        """,
        nativeQuery = true,
    )
    fun findAllSummaries(): List<DeliveryPlanSummaryProjection>

    fun findByIdAndDriverId(planId: Long, driverId: Long): DeliveryPlan?

    // 삭제 배치용 조회. 마지막으로 처리한 ID 다음부터 한 페이지씩 읽는다.
    // Offset 대신 Page 사용하는 이유 : 읽으면서 지우기 때문에 OFFSET 기준이 매번 어긋나 행을 건너뜀.
    // ID 를 커서로 쓰면 삭제 여부와 무관하게 빠짐없이 read 가능.
    // Pageable 은 정렬이 아니라 LIMIT 용도로만 사용.
    @Query(
        """
        SELECT p FROM DeliveryPlan p
        WHERE p.status = :status
          AND p.completedAt < :cutoff
          AND p.id > :lastId
        ORDER BY p.id
        """,
    )
    fun findExpiredPlans(
        @Param("status") status: DeliveryPlanStatus,
        @Param("cutoff") cutoff: LocalDateTime,
        @Param("lastId") lastId: Long,
        pageable: Pageable,
    ): List<DeliveryPlan>
}
