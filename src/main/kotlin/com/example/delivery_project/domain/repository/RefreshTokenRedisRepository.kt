package com.example.delivery_project.domain.repository

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration

@Repository
class RefreshTokenRedisRepository(
    private val redisTemplate: StringRedisTemplate,
) {
    fun save(userId: Long, token: String, ttl: Duration) {
        redisTemplate.opsForValue().set(KEY_PREFIX + userId, token, ttl)
    }

    fun findByUserId(userId: Long): String? =
        redisTemplate.opsForValue().get(KEY_PREFIX + userId)

    fun deleteByUserId(userId: Long) {
        redisTemplate.delete(KEY_PREFIX + userId)
    }

    companion object {
        private const val KEY_PREFIX = "refreshToken:"
    }
}
