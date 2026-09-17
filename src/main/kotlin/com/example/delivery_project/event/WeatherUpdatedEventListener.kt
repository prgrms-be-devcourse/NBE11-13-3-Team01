package com.example.delivery_project.event

import com.example.delivery_project.domain.repository.WeatherCacheRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class WeatherUpdatedEventListener(private val weatherCacheRepository: WeatherCacheRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Weather DB 트랜잭션이 실제로 커밋된 뒤에만 캐시를 무효화한다.
    // 커밋 전에 지우면, 그사이 다른 요청이 아직 반영되지 않은(곧 롤백될 수도 있는)
    // 값을 DB에서 다시 읽어 캐시에 채워 넣을 수 있다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun evictCacheAfterWeatherUpdated(event: WeatherUpdatedEvent) {
        runCatching { weatherCacheRepository.delete(event.nx, event.ny) }
            .onFailure {
                log.warn("날씨 캐시 무효화 실패. TTL 만료로 자연 정리됩니다. nx={}, ny={}", event.nx, event.ny, it)
            }
    }
}
