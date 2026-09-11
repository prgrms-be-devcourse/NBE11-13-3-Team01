package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.dto.projection.DeliveryPlanSummaryProjection
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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
}
