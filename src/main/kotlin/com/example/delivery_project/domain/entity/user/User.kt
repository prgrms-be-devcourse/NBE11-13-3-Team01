package com.example.delivery_project.domain.entity.user

import com.example.delivery_project.enums.Role
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class User(
    id: Long? = null,
    loginId: String,
    password: String?,
    name: String,
    role: Role = Role.ROLE_DELIVERY_DRIVER,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set

    @field:Column(nullable = false, unique = true)
    var loginId: String = loginId
        protected set

    @field:Column
    var password: String? = password
        protected set

    @field:Column(nullable = false)
    var name: String = name
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var role: Role = role
        protected set

    @field:Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
        protected set

    companion object {
        fun of(
            id: Long?,
            loginId: String,
            password: String?,
            name: String,
            role: Role,
        ): User = User(id, loginId, password, name, role)

        fun of(
            loginId: String,
            password: String?,
            name: String,
            role: Role,
        ): User = User(null, loginId, password, name, role)

        fun of(
            loginId: String,
            password: String?,
            name: String,
        ): User = User(null, loginId, password, name, Role.ROLE_DELIVERY_DRIVER)
    }

    fun withdraw() {
        deletedAt = LocalDateTime.now()
    }

    fun isWithdrawn(): Boolean =
        deletedAt != null
}
