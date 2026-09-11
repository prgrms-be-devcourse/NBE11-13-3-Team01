package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.enums.Role
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface UserRepository : JpaRepository<User, Long> {
    @Query("select u from User u where u.id = :id")
    fun findUserById(id: Long): User?

    fun existsByLoginId(loginId: String): Boolean

    fun findByLoginId(loginId: String): User?

    fun findAllByRoleOrderByNameAsc(role: Role): List<User>
}
