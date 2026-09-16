package com.example.delivery_project.security.auth

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.domain.repository.UserRepository
import com.example.delivery_project.enums.Role
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.security.core.userdetails.UsernameNotFoundException

class CustomUserDetailsServiceTest {
    private val userRepository = mock<UserRepository>()
    private val userDetailsService = CustomUserDetailsService(userRepository)

    @Test
    fun `로그인 아이디로 사용자 정보를 조회한다`() {
        val user = User(
            id = 1L,
            loginId = "driver",
            password = "password",
            name = "배송기사",
            role = Role.ROLE_DELIVERY_DRIVER,
        )
        whenever(userRepository.findByLoginIdAndDeletedAtIsNull("driver")).thenReturn(user)

        val result = userDetailsService.loadUserByUsername("driver")

        assertThat(result.user).isEqualTo(user)
    }

    @Test
    fun `사용자가 없으면 UsernameNotFoundException이 발생한다`() {
        whenever(userRepository.findByLoginIdAndDeletedAtIsNull("missing")).thenReturn(null)

        assertThatThrownBy { userDetailsService.loadUserByUsername("missing") }
            .isInstanceOf(UsernameNotFoundException::class.java)
    }
}
