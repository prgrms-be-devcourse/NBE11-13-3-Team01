package com.example.delivery_project.service

import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.request.UserJoinRequest
import com.example.delivery_project.dto.request.UserLoginRequest
import com.example.delivery_project.exception.AuthException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.security.auth.CustomUserDetails
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authenticationManager: AuthenticationManager,
    private val tokenService: TokenService,
    private val deliveryPlanRepository: DeliveryPlanRepository,
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

    /**
     * 회원 탈퇴.
     *
     * 진행 중인 배송 업무(READY, DELIVERING)가 있으면 거절한다. 그대로 탈퇴시키면
     * 탈퇴한 기사에게 묶인 배송 계획이 남아 아무도 손댈 수 없게 된다.
     *
     * ## 왜 기사 행을 먼저 잠그는가
     *
     * "보유 0건" 검사와 탈퇴 사이에 다른 요청이 업무를 수령하면 검사가 무의미해진다.
     * [DeliveryPlanClaimService] 가 수령·반납에서 쓰는 것과 **같은 락, 같은 순서**
     * (users -> delivery_plan)로 잡아 두 경로를 직렬화한다.
     *
     * ## 왜 READ_COMMITTED 인가
     *
     * MySQL 기본 격리 수준(REPEATABLE READ)에서는 트랜잭션이 스냅숏을 잡고 나면
     * 그 뒤 일반 SELECT 가 낡은 값을 본다. 잠금 읽기 바로 뒤에 오는 보유 업무 수 조회가
     * 최신 커밋을 보게 해야 하므로 수령 경로와 같은 격리 수준을 쓴다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun withdraw(userId: Long) {

        val user = userRepository.findUserByIdForUpdate(userId)
                    ?: throw BusinessException(AuthException.AUTHENTICATION_REQUIRED)

        val activePlanCount = deliveryPlanRepository.countByDriverIdAndStatusIn(
            userId,
            DeliveryPlanStatus.ACTIVE_STATUSES,
        )
        if (activePlanCount > 0) {
            log.info("[USER] 진행 중인 배송으로 탈퇴 거절 userId: {}, activePlans: {}", userId, activePlanCount)
            throw BusinessException(AuthException.WITHDRAW_BLOCKED_BY_ACTIVE_DELIVERY)
        }

        // deletedAt 필드 변경, 로그아웃
        user.withdraw()
        tokenService.logout(userId)

        log.info("[USER] 회원 탈퇴 완료 userId: {}, loginId: {}", user.id, user.loginId)

    }
}
