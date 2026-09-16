package com.example.delivery_project.domain.repository

import com.example.delivery_project.dto.cache.WeatherRiskCache
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Duration

@Repository
class WeatherCacheRepository(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun find(nx: Int, ny: Int): WeatherRiskCache? =
        redisTemplate.opsForValue().get(key(nx, ny))
            ?.let { objectMapper.readValue(it, WeatherRiskCache::class.java) }

    fun save(nx: Int, ny: Int, cache: WeatherRiskCache, ttl: Duration) {
        redisTemplate.opsForValue().set(key(nx, ny), objectMapper.writeValueAsString(cache), ttl)
    }

    fun delete(nx: Int, ny: Int) {
        redisTemplate.delete(key(nx, ny))
    }

    private fun key(nx: Int, ny: Int): String = "$KEY_PREFIX$nx:$ny"

    companion object {
        private const val KEY_PREFIX = "weather:risk:"
    }
}
