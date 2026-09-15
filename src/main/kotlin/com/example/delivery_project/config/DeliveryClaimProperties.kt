package com.example.delivery_project.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "delivery.claim")
class DeliveryClaimProperties {
    /** 배송 기사 한 명이 동시에 보유할 수 있는 진행 중(READY/DELIVERING) 배송 업무 수 */
    var maxActivePlans: Int = 3
}
