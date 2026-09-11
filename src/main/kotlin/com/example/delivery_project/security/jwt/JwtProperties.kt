package com.example.delivery_project.security.jwt

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

@Component
@ConfigurationProperties(prefix = "jwt")
class JwtProperties {
    lateinit var issuer: String
    lateinit var secretKey: String
    lateinit var accessTokenValidity: Duration
    lateinit var refreshTokenValidity: Duration
}
