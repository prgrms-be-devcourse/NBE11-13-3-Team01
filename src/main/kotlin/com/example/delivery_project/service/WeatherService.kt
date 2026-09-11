package com.example.delivery_project.service

import com.example.delivery_project.dto.request.WeatherRequest
import com.example.delivery_project.service.component.WeatherUpdater
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class WeatherService(private val weatherUpdater: WeatherUpdater) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun save(request: WeatherRequest): Boolean = weatherUpdater.update(request)

    fun resolveLatestBaseDateTime(): WeatherUpdater.BaseDateTime = weatherUpdater.resolveLatestBaseDateTime()
}
