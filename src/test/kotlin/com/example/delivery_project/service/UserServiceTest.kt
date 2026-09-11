package com.example.delivery_project.service

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.dto.request.UserJoinRequest
import com.example.delivery_project.dto.request.UserLoginRequest
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

    private fun assertAuthException(expected: AuthException, action: () -> Unit) {
        assertThat(assertThrows<BusinessException>(action).errorCode).isEqualTo(expected)
    }
}
