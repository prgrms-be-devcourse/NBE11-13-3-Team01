package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.delivery.DeliveryPlan
import com.example.delivery_project.dto.projection.DeliveryPlanSummaryProjection
import com.example.delivery_project.dto.projection.DeliveryStatisticsProjection
import com.example.delivery_project.dto.projection.OpenDeliveryPlanSummaryProjection
import com.example.delivery_project.enums.DeliveryPlanStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface DeliveryPlanRepository : JpaRepository<DeliveryPlan, Long> {
    @Query(
        """
        select distinct p
        from DeliveryPlan p
        left join fetch p.driver
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

    @Query("select p from DeliveryPlan p left join fetch p.driver where p.id = :planId")
    fun findWithDriverById(planId: Long): DeliveryPlan?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
        UPDATE delivery_plan
        SET driver_id   = :driverId,
            status      = 'READY',
            assigned_at = :claimedAt,
            version     = version + 1
        WHERE id        = :planId
          AND status    = 'OPEN'
          AND driver_id IS NULL
          AND (
                public_at IS NULL
                OR public_at <= :claimedAt
                OR EXISTS (
                    SELECT 1
                    FROM delivery_plan_priority_driver pd
                    WHERE pd.delivery_plan_id = :planId
                      AND pd.driver_id = :driverId
                )
              )
        """,
        nativeQuery = true,
    )
    fun claimIfOpen(
        @Param("planId") planId: Long,
        @Param("driverId") driverId: Long,
        @Param("claimedAt") claimedAt: LocalDateTime,
    ): Int

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        value = """
        UPDATE delivery_plan
        SET driver_id   = NULL,
            status      = 'OPEN',
            assigned_at = NULL,
            public_at   = NULL,
            version     = version + 1
        WHERE id        = :planId
          AND driver_id = :driverId
          AND status    = 'READY'
        """,
        nativeQuery = true,
    )
    fun releaseIfOwnedAndReady(
        @Param("planId") planId: Long,
        @Param("driverId") driverId: Long,
    ): Int

    fun countByDriverIdAndStatusIn(driverId: Long, statuses: Collection<DeliveryPlanStatus>): Long

    @Query(
        value = """
        SELECT
            p.id AS planId,
            u.id AS driverId,
            u.login_id AS driverLoginId,
            u.name AS driverName,
            p.departure_location AS departureLocation,
            p.scheduled_departure_at AS scheduledDepartureAt,
            p.assigned_at AS assignedAt,
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
        LEFT JOIN users u ON u.id = p.driver_id
        LEFT JOIN delivery_stop s ON s.delivery_plan_id = p.id
        LEFT JOIN delivery_item i ON i.delivery_stop_id = s.id
        LEFT JOIN risk_assessment ra ON ra.delivery_stop_id = s.id
        WHERE p.driver_id = :driverId
        GROUP BY
            p.id, u.id, u.login_id, u.name, p.departure_location,
            p.scheduled_departure_at, p.assigned_at, p.actual_departure_at,
            p.completed_at, p.status
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
            CAST(NULL AS UNSIGNED) AS driverId,
            CAST(NULL AS CHAR) AS driverLoginId,
            CAST(NULL AS CHAR) AS driverName,
            p.departure_location AS departureLocation,
            p.scheduled_departure_at AS scheduledDepartureAt,
            p.assigned_at AS assignedAt,
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
        LEFT JOIN delivery_stop s ON s.delivery_plan_id = p.id
        LEFT JOIN delivery_item i ON i.delivery_stop_id = s.id
        LEFT JOIN risk_assessment ra ON ra.delivery_stop_id = s.id
        WHERE p.status = 'OPEN'
        GROUP BY
            p.id, p.departure_location, p.scheduled_departure_at, p.assigned_at,
            p.actual_departure_at, p.completed_at, p.status
        ORDER BY p.scheduled_departure_at ASC
        """,
        nativeQuery = true,
    )
    fun findAllOpenSummaries(): List<DeliveryPlanSummaryProjection>

    @Query(
        value = """
        SELECT
            p.id AS planId,
            CAST(NULL AS UNSIGNED) AS driverId,
            CAST(NULL AS CHAR) AS driverLoginId,
            CAST(NULL AS CHAR) AS driverName,
            p.departure_location AS departureLocation,
            p.scheduled_departure_at AS scheduledDepartureAt,
            p.assigned_at AS assignedAt,
            p.actual_departure_at AS actualDepartureAt,
            p.completed_at AS completedAt,
            p.status AS status,
            p.public_at AS publicAt,
            MAX(pd.priority_rank) AS priorityRank,
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
        LEFT JOIN delivery_plan_priority_driver pd
               ON pd.delivery_plan_id = p.id AND pd.driver_id = :driverId
        LEFT JOIN delivery_stop s ON s.delivery_plan_id = p.id
        LEFT JOIN delivery_item i ON i.delivery_stop_id = s.id
        LEFT JOIN risk_assessment ra ON ra.delivery_stop_id = s.id
        WHERE p.status = 'OPEN'
        GROUP BY
            p.id, p.departure_location, p.scheduled_departure_at, p.assigned_at,
            p.actual_departure_at, p.completed_at, p.status, p.public_at
        ORDER BY p.scheduled_departure_at ASC
        """,
        nativeQuery = true,
    )
    fun findOpenSummariesForDriver(
        @Param("driverId") driverId: Long,
    ): List<OpenDeliveryPlanSummaryProjection>

    @Query(
        value = """
        SELECT
            p.id AS planId,
            u.id AS driverId,
            u.login_id AS driverLoginId,
            u.name AS driverName,
            p.departure_location AS departureLocation,
            p.scheduled_departure_at AS scheduledDepartureAt,
            p.assigned_at AS assignedAt,
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
        LEFT JOIN users u ON u.id = p.driver_id
        LEFT JOIN delivery_stop s ON s.delivery_plan_id = p.id
        LEFT JOIN delivery_item i ON i.delivery_stop_id = s.id
        LEFT JOIN risk_assessment ra ON ra.delivery_stop_id = s.id
        GROUP BY
            p.id, u.id, u.login_id, u.name, p.departure_location,
            p.scheduled_departure_at, p.assigned_at, p.actual_departure_at,
            p.completed_at, p.status
        ORDER BY p.scheduled_departure_at ASC
        """,
        nativeQuery = true,
    )
    fun findAllSummaries(): List<DeliveryPlanSummaryProjection>

    @Query(
        value = """
        SELECT
            COUNT(DISTINCT p.id) AS totalPlans,
            COUNT(DISTINCT CASE WHEN p.status = 'OPEN' THEN p.id END) AS openPlans,
            COUNT(DISTINCT CASE WHEN p.status = 'READY' THEN p.id END) AS readyPlans,
            COUNT(DISTINCT CASE WHEN p.status = 'DELIVERING' THEN p.id END) AS deliveringPlans,
            COUNT(DISTINCT CASE WHEN p.status = 'COMPLETED' THEN p.id END) AS completedPlans,
            COUNT(DISTINCT s.id) AS totalStops,
            COUNT(DISTINCT CASE WHEN s.status <> 'COMPLETED' THEN s.id END) AS remainingStops,
            COALESCE(SUM(i.quantity), 0) AS totalBoxes,
            COALESCE(SUM(CASE WHEN s.status <> 'COMPLETED' THEN i.quantity ELSE 0 END), 0) AS remainingBoxes,
            COUNT(DISTINCT CASE
                WHEN s.status <> 'COMPLETED' AND ra.level = 'DANGER' THEN s.id
            END) AS dangerStops
        FROM delivery_plan p
        LEFT JOIN delivery_stop s ON s.delivery_plan_id = p.id
        LEFT JOIN delivery_item i ON i.delivery_stop_id = s.id
        LEFT JOIN risk_assessment ra ON ra.delivery_stop_id = s.id
        """,
        nativeQuery = true,
    )
    fun getDeliveryStatistics(): DeliveryStatisticsProjection

    fun findByIdAndDriverId(planId: Long, driverId: Long): DeliveryPlan?
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
