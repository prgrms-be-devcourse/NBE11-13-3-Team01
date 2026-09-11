package com.example.delivery_project.security.auth

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.enums.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CustomUserDetailsTest {
    @Test
    fun `사용자 정보와 권한을 Spring Security 형식으로 제공한다`() {
        val user = User(
            id = 1L,
            loginId = "admin",
            password = "encoded-password",
            name = "관리자",
            role = Role.ROLE_ADMIN,
        )
        val details = CustomUserDetails(user)

        assertThat(details.getUsername()).isEqualTo("admin")
        assertThat(details.getPassword()).isEqualTo("encoded-password")
        assertThat(details.getAuthorities().map { it.authority }).containsExactly(Role.ROLE_ADMIN.name)
        assertThat(details.isAccountNonExpired()).isTrue()
        assertThat(details.isAccountNonLocked()).isTrue()
        assertThat(details.isCredentialsNonExpired()).isTrue()
        assertThat(details.isEnabled()).isTrue()
    }
}
