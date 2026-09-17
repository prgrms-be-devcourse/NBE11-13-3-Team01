package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.DeliveryPlanRepository
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.request.UserJoinRequest
import com.example.delivery_project.dto.request.UserLoginRequest
import com.example.delivery_project.enums.DeliveryPlanStatus
import com.example.delivery_project.enums.Role
import com.example.delivery_project.exception.AuthException
import com.example.delivery_project.exception.global.BusinessException
import com.example.delivery_project.security.auth.CustomUserDetails
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder

@ExtendWith(MockitoExtension::class)
class UserServiceTest {
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var passwordEncoder: PasswordEncoder
    @Mock lateinit var authenticationManager: AuthenticationManager
    @Mock lateinit var tokenService: TokenService
    @Mock lateinit var deliveryPlanRepository: DeliveryPlanRepository
    @InjectMocks lateinit var userService: UserService

    @Test
    fun 회원가입하면_비밀번호를_암호화하고_DRIVER로_저장한다() {
        val request = UserJoinRequest("driver", "plain", "배송기사")
        whenever(userRepository.existsByLoginId("driver")).thenReturn(false)
        whenever(passwordEncoder.encode("plain")).thenReturn("encoded")
        userService.join(request)
        val captor = argumentCaptor<User>()
        verify(userRepository).save(captor.capture())
        val savedUser = captor.firstValue
        assertThat(savedUser.loginId).isEqualTo("driver")
        assertThat(savedUser.password).isEqualTo("encoded")
        assertThat(savedUser.name).isEqualTo("배송기사")
        assertThat(savedUser.role).isEqualTo(Role.ROLE_DELIVERY_DRIVER)
    }

    @Test
    fun 중복_아이디는_회원가입할_수_없다() {
        val request = UserJoinRequest("driver", "plain", "배송기사")
        whenever(userRepository.existsByLoginId("driver")).thenReturn(true)
        assertAuthException(AuthException.DUPLICATE_LOGIN_ID) { userService.join(request) }
        verify(passwordEncoder, never()).encode(any())
        verify(userRepository, never()).save(any<User>())
    }

    @Test
    fun 로그인에_성공하면_인증된_사용자의_토큰을_발급한다() {
        val user = User.of(1L, "driver", "encoded", "배송기사", Role.ROLE_DELIVERY_DRIVER)
        val authentication = mock<Authentication>()
        whenever(authentication.principal).thenReturn(CustomUserDetails(user))
        whenever(authenticationManager.authenticate(any())).thenReturn(authentication)
        val expected = TokenService.TokenPair("access", "refresh")
        whenever(tokenService.issueToken(user)).thenReturn(expected)
        val result = userService.login(UserLoginRequest("driver", "plain"))
        val captor = argumentCaptor<UsernamePasswordAuthenticationToken>()
        verify(authenticationManager).authenticate(captor.capture())
        assertThat(result).isEqualTo(expected)
        assertThat(captor.firstValue.principal).isEqualTo("driver")
        assertThat(captor.firstValue.credentials).isEqualTo("plain")
        verify(tokenService).issueToken(user)
    }

    @Test
    fun 인증에_실패하면_INVALID_LOGIN을_반환한다() {
        whenever(authenticationManager.authenticate(any())).thenThrow(BadCredentialsException("bad credentials"))
        assertAuthException(AuthException.INVALID_LOGIN) { userService.login(UserLoginRequest("driver", "wrong")) }
        verify(tokenService, never()).issueToken(any())
    }

    @Test
    fun 회원_탈퇴하면_soft_delete되고_RefreshToken을_로그아웃한다() {
        val user = User.of(1L, "driver", "encoded", "배송기사", Role.ROLE_DELIVERY_DRIVER)
        whenever(userRepository.findUserByIdForUpdate(1L)).thenReturn(user)
        whenever(deliveryPlanRepository.countByDriverIdAndStatusIn(1L, DeliveryPlanStatus.ACTIVE_STATUSES))
            .thenReturn(0L)

        userService.withdraw(1L)

        assertThat(user.isWithdrawn()).isTrue()
        verify(tokenService).logout(1L)
    }

    @Test
    fun 진행_중인_배송_업무가_있으면_탈퇴할_수_없다() {
        val user = User.of(1L, "driver", "encoded", "배송기사", Role.ROLE_DELIVERY_DRIVER)
        whenever(userRepository.findUserByIdForUpdate(1L)).thenReturn(user)
        whenever(deliveryPlanRepository.countByDriverIdAndStatusIn(1L, DeliveryPlanStatus.ACTIVE_STATUSES))
            .thenReturn(2L)

        assertAuthException(AuthException.WITHDRAW_BLOCKED_BY_ACTIVE_DELIVERY) { userService.withdraw(1L) }

        // 거절했으면 세션도 그대로 둬야 한다. 로그아웃만 되고 계정이 남으면 사용자가 상태를 오해한다.
        assertThat(user.isWithdrawn()).isFalse()
        verify(tokenService, never()).logout(any())
    }

    @Test
    fun 보유_업무_수를_세기_전에_기사_행을_먼저_잠근다() {
        // 잠금보다 카운트가 먼저 나가면 그 사이에 수령이 끼어들어 검사가 무의미해진다.
        val user = User.of(1L, "driver", "encoded", "배송기사", Role.ROLE_DELIVERY_DRIVER)
        whenever(userRepository.findUserByIdForUpdate(1L)).thenReturn(user)
        whenever(deliveryPlanRepository.countByDriverIdAndStatusIn(1L, DeliveryPlanStatus.ACTIVE_STATUSES))
            .thenReturn(0L)

        userService.withdraw(1L)

        inOrder(userRepository, deliveryPlanRepository) {
            verify(userRepository).findUserByIdForUpdate(1L)
            verify(deliveryPlanRepository).countByDriverIdAndStatusIn(1L, DeliveryPlanStatus.ACTIVE_STATUSES)
        }
    }

    @Test
    fun 이미_탈퇴했거나_존재하지_않는_회원은_탈퇴할_수_없다() {
        whenever(userRepository.findUserByIdForUpdate(1L)).thenReturn(null)

        assertAuthException(AuthException.AUTHENTICATION_REQUIRED) { userService.withdraw(1L) }
        verify(tokenService, never()).logout(any())
        verify(deliveryPlanRepository, never()).countByDriverIdAndStatusIn(any(), any())
    }

    private fun assertAuthException(expected: AuthException, action: () -> Unit) {
        assertThat(assertThrows<BusinessException>(action).errorCode).isEqualTo(expected)
    }
}
