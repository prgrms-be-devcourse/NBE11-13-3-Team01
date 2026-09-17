package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.dto.projection.DriverWorkloadProjection
import com.example.delivery_project.enums.Role
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserRepository : JpaRepository<User, Long> {
    // 탈퇴 여부 확인을 포함 - 재발급 시 사용
    @Query("select u from User u where u.id = :id AND u.deletedAt IS NULL")
    fun findUserByIdAndDeletedAtIsNull(id: Long): User?

    /**
     * 기사 행 자체를 비관적으로 잠근다. 탈퇴한 회원은 활성 기사로 취급하지 않으므로 제외한다.
     *
     * 계획 행의 조건부 UPDATE 는 "한 계획을 한 기사만 가져간다"까지만 보장하고,
     * "한 기사가 동시에 N건을 초과해 가져가지 않는다"는 보장하지 못한다.
     * 같은 기사의 claim 요청을 이 락으로 직렬화해 보유 수량 검사와 수령을 원자적으로 만든다.
     *
     * 데드락을 막기 위해 claim 경로에서는 항상 users -> delivery_plan 순으로만 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id AND u.deletedAt IS NULL")
    fun findUserByIdForUpdate(id: Long): User?

    fun existsByLoginId(loginId: String): Boolean

    // 탈퇴 여부 확인을 포함 - 로그인 시 사용
    fun findByLoginIdAndDeletedAtIsNull(loginId: String): User?

    fun findAllByRoleAndDeletedAtIsNullOrderByNameAsc(role: Role): List<User>

    /**
     * 기사별 현재 보유 업무량 집계. 배송 기사 추천 스코어링의 입력값이다.
     * 진행 중(READY / DELIVERING) 계획만 집계하며, 업무가 없는 기사도 0으로 포함된다.
     */
    @Query(
        value = """
        SELECT
            u.id AS driverId,
            u.login_id AS driverLoginId,
            u.name AS driverName,
            COUNT(DISTINCT CASE
                WHEN p.status IN ('READY', 'DELIVERING') THEN p.id
            END) AS activePlans,
            COUNT(DISTINCT CASE
                WHEN p.status IN ('READY', 'DELIVERING') AND s.status <> 'COMPLETED' THEN s.id
            END) AS remainingStops,
            COALESCE(SUM(CASE
                WHEN p.status IN ('READY', 'DELIVERING') AND s.status <> 'COMPLETED' THEN i.quantity
                ELSE 0
            END), 0) AS remainingBoxes,
            COUNT(DISTINCT CASE
                WHEN p.status IN ('READY', 'DELIVERING')
                     AND s.status <> 'COMPLETED'
                     AND ra.level = 'DANGER' THEN s.id
            END) AS dangerStops
        FROM users u
        LEFT JOIN delivery_plan p ON p.driver_id = u.id
        LEFT JOIN delivery_stop s ON s.delivery_plan_id = p.id
        LEFT JOIN delivery_item i ON i.delivery_stop_id = s.id
        LEFT JOIN risk_assessment ra ON ra.delivery_stop_id = s.id
        WHERE u.role = :role
          AND u.deleted_at IS NULL
        GROUP BY u.id, u.login_id, u.name
        ORDER BY u.name ASC
        """,
        nativeQuery = true,
    )
    fun findDriverWorkloads(@Param("role") role: String): List<DriverWorkloadProjection>
}
