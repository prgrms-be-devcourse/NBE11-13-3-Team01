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

    /**
     * 선착순 수령의 1차 방어선.
     *
     * `status = 'OPEN' AND driver_id IS NULL` 조건을 UPDATE 문에 함께 실어 보내
     * InnoDB 가 해당 행을 잠근 상태에서 조건을 재평가하도록 만든다.
     * 경합한 요청 중 단 한 건만 1을 돌려받고, 나머지는 0을 돌려받는다.
     * 별도 조회-검증-저장 단계가 없으므로 lost update 가 구조적으로 발생할 수 없다.
     *
     * 우선권 윈도우 판정도 같은 문장에 담는다.
     * 애플리케이션에서 "지금 공개됐나"를 먼저 읽고 판단하면 그 사이에 공개 시각이 지나거나
     * 다른 기사가 수령해 버리는 틈이 생긴다. 공개 여부와 우선권 보유 여부를 UPDATE 조건으로 내려
     * 윈도우 경계에서의 경합까지 DB 가 한 번에 판정하게 한다.
     *
     * 갱신 대상(delivery_plan)과 다른 테이블을 참조하는 서브쿼리이므로
     * MySQL 의 "같은 테이블을 갱신하며 서브쿼리로 조회할 수 없다" 제약에 걸리지 않는다.
     */
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

    /**
     * 반납도 동일하게 원자적 조건부 UPDATE 로 처리한다.
     * 소유자 본인이면서 아직 출발 전(READY)일 때만 1을 돌려준다.
     *
     * 반납된 업무는 `public_at` 을 비워 즉시 전체 공개한다.
     * 우선권을 다시 열면 추천 상위 기사가 수령·반납을 반복해 같은 업무를 계속 선점할 수 있다.
     */
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

    /**
     * 아직 아무도 수령하지 않은 배송 업무 목록.
     * 기사 정보가 없으므로 users 는 반드시 LEFT JOIN 이어야 한다.
     */
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

    /**
     * 특정 기사 관점의 미배정 업무 목록.
     *
     * 우선 수령 윈도우가 열린 업무도 목록에는 노출하되, 본인의 우선권 순위(`priorityRank`)와
     * 전체 공개 시각(`publicAt`)을 함께 내려 화면에서 "지금 가져갈 수 있는지"를 구분할 수 있게 한다.
     * 우선권 조인은 `(plan, driver)` 유니크 제약 덕분에 계획당 최대 한 행이라 집계값을 왜곡하지 않는다.
     */
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
