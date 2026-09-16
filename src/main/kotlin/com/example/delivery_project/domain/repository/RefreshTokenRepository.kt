package com.example.delivery_project.domain.repository

import com.example.delivery_project.domain.entity.token.RefreshToken
import org.springframework.data.jpa.repository.JpaRepository

interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {

    fun findByUserId(userId: Long): RefreshToken?

    fun deleteByUserId(userId: Long)
}