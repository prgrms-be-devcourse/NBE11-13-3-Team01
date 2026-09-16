package com.example.delivery_project.domain.entity.token

import com.example.delivery_project.domain.entity.user.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "refresh_token")
class RefreshToken(
    id: Long? = null,
    user: User,
    tokenHash: String,
    expiresAt: LocalDateTime,
) {

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = id
        protected set

    @field:OneToOne(fetch = FetchType.LAZY)
    @field:JoinColumn(
        name = "user_id",
        nullable = false,
        unique = true,
    )
    var user: User = user
        protected set

    @field:Column(
        name = "token_hash",
        nullable = false,
        length = 64,
    )
    var tokenHash: String = tokenHash
        protected set

    @field:Column(
        name = "expires_at",
        nullable = false,
    )
    var expiresAt: LocalDateTime = expiresAt
        protected set

    fun update(
        tokenHash: String,
        expiresAt: LocalDateTime,
    ) {
        this.tokenHash = tokenHash
        this.expiresAt = expiresAt
    }

    companion object {
        fun of(
            user: User,
            tokenHash: String,
            expiresAt: LocalDateTime,
        ) = RefreshToken(
            user = user,
            tokenHash = tokenHash,
            expiresAt = expiresAt,
        )
    }
}