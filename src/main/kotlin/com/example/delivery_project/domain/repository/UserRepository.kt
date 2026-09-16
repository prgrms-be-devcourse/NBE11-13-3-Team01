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
    @Query("select u from User u where u.id = :id")
    fun findUserById(id: Long): User?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    fun findUserByIdForUpdate(id: Long): User?

    fun existsByLoginId(loginId: String): Boolean

    fun findByLoginId(loginId: String): User?

    fun findAllByRoleOrderByNameAsc(role: Role): List<User>

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
        GROUP BY u.id, u.login_id, u.name
        ORDER BY u.name ASC
        """,
        nativeQuery = true,
    )
    fun findDriverWorkloads(@Param("role") role: String): List<DriverWorkloadProjection>
}
