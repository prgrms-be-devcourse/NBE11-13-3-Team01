package com.example.delivery_project.security.filter

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.enums.Role
import com.example.delivery_project.security.jwt.TokenProvider
import com.example.delivery_project.security.jwt.TokenStatus
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

class TokenAuthenticationFilterTest {
    private val tokenProvider = mock<TokenProvider>()
    private val filterChain = mock<FilterChain>()
    private val filter = TokenAuthenticationFilter(tokenProvider)

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `유효한 Bearer 토큰이면 인증정보를 SecurityContext에 저장한다`() {
        val request = MockHttpServletRequest().apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer access-token")
        }
        val response = MockHttpServletResponse()
        val user = User(
            id = 1L,
            loginId = "driver",
            password = null,
            name = "배송기사",
            role = Role.ROLE_DELIVERY_DRIVER,
        )
        val authentication = UsernamePasswordAuthenticationToken(user, "access-token")
        whenever(tokenProvider.validateToken("access-token")).thenReturn(TokenStatus.VALID)
        whenever(tokenProvider.getTokenDetails("access-token")).thenReturn(user)
        whenever(tokenProvider.getAuthentication(user, "access-token")).thenReturn(authentication)

        filter.doFilter(request, response, filterChain)

        assertThat(SecurityContextHolder.getContext().authentication).isEqualTo(authentication)
        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `토큰이 없어도 다음 필터를 실행한다`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, filterChain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verifyNoInteractions(tokenProvider)
        verify(filterChain).doFilter(request, response)
    }

    @Test
    fun `유효하지 않은 토큰은 인증정보를 만들지 않는다`() {
        val request = MockHttpServletRequest().apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
        }
        val response = MockHttpServletResponse()
        whenever(tokenProvider.validateToken("invalid-token")).thenReturn(TokenStatus.INVALID)

        filter.doFilter(request, response, filterChain)

        assertThat(SecurityContextHolder.getContext().authentication).isNull()
        verify(tokenProvider, never()).getTokenDetails("invalid-token")
        verify(filterChain).doFilter(request, response)
    }
}
