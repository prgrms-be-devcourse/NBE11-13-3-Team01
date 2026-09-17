package com.example.delivery_project.config

import com.example.delivery_project.exception.AuthException
import com.example.delivery_project.exception.ErrorCode
import com.example.delivery_project.exception.global.ErrorResponse
import com.example.delivery_project.security.filter.TokenAuthenticationFilter
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.ObjectMapper

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig(
    private val tokenAuthenticationFilter: TokenAuthenticationFilter,
    private val objectMapper: ObjectMapper,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .logout { it.disable() }
        .formLogin { it.disable() }
        .httpBasic { it.disable() }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests { authorization ->
            authorization.requestMatchers(
                "/",
                "/index.html",
                "/favicon.svg",
                "/icons.svg",
                "/assets/**",
                "/login",
                "/plans",
                "/plans/**",
                "/api/users/join",
                "/api/users/login",
                "/api/tokens/refresh",
                "/api/dev/tokens",
                "/swagger-ui/**",
                "/v3/api-docs/**",
                "/error",
                "/actuator/**",
            ).permitAll()
                .anyRequest().authenticated()
        }
        .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        .exceptionHandling {
            it.accessDeniedHandler(accessDeniedHandler())
                .authenticationEntryPoint(authenticationEntryPoint())
        }
        .build()

    @Bean
    fun passwordEncoder(): BCryptPasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun authenticationManager(configuration: AuthenticationConfiguration): AuthenticationManager =
        configuration.authenticationManager

    @Bean
    fun accessDeniedHandler(): AccessDeniedHandler = AccessDeniedHandler { _, response, _ ->
        sendError(response, AuthException.ACCESS_DENIED)
    }

    @Bean
    fun authenticationEntryPoint(): AuthenticationEntryPoint = AuthenticationEntryPoint { _, response, _ ->
        sendError(response, AuthException.AUTHENTICATION_REQUIRED)
    }

    private fun sendError(response: HttpServletResponse, errorCode: ErrorCode) {
        response.status = errorCode.status.value()
        response.contentType = "application/json;charset=UTF-8"
        objectMapper.writeValue(
            response.writer,
            ErrorResponse(status = errorCode.status, code = errorCode.code, message = errorCode.message),
        )
    }
}
