package com.example.delivery_project.security.auth

import com.example.delivery_project.domain.entity.user.User
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

class CustomUserDetails(val user: User) : UserDetails {
    override fun getAuthorities(): Collection<GrantedAuthority> =
        listOf(SimpleGrantedAuthority(user.role.name))

    override fun getPassword(): String? = user.password

    override fun getUsername(): String = user.loginId

    // 계정 만료, 잠금, 비밀번호 만료 및 비활성화 기능은 사용하지 않는다.
    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = true

    override fun isCredentialsNonExpired(): Boolean = true

    override fun isEnabled(): Boolean = true
}
