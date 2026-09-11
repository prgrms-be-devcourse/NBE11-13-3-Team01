package com.example.delivery_project.service

import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.request.UserJoinRequest
import com.example.delivery_project.dto.request.UserLoginRequest
import com.example.delivery_project.exception.AuthException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.security.auth.CustomUserDetails
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authenticationManager: AuthenticationManager,
    private val tokenService: TokenService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun join(request: UserJoinRequest) {
        if (userRepository.existsByLoginId(request.loginId)) {
            throw BusinessException(AuthException.DUPLICATE_LOGIN_ID)
        }
        val user = request.toUser(requireNotNull(passwordEncoder.encode(request.password)))
        userRepository.save(user)
        log.info("[AUTH] 회원가입 성공 userId: {}, loginId: {} role: {}", user.id, user.loginId, user.role)
    }

    @Transactional
    fun login(request: UserLoginRequest): TokenService.TokenPair {
        try {
            val authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken(request.loginId, request.password),
            )
            val user = (authentication.principal as CustomUserDetails).user
            val tokenPair = tokenService.issueToken(user)
            log.info("[AUTH] 로그인 성공 userId: {}, loginId: {}", user.id, user.loginId)
            return tokenPair
        } catch (e: AuthenticationException) {
            throw BusinessException(AuthException.INVALID_LOGIN)
        }
    }
}
