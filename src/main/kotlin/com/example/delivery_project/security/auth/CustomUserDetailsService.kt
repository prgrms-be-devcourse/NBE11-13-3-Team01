package com.example.delivery_project.security.auth

import com.example.delivery_project.domain.repository.UserRepository
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class CustomUserDetailsService(private val userRepository: UserRepository) : UserDetailsService {
    override fun loadUserByUsername(username: String): CustomUserDetails {
        // 탈퇴 여부 확인
        val user = userRepository.findByLoginIdAndDeletedAtIsNull(username)
            ?: throw UsernameNotFoundException("사용자를 찾을 수 없습니다.")
        return CustomUserDetails(user)
    }
}
