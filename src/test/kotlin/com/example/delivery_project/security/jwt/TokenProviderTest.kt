package com.example.delivery_project.security.jwt

import com.example.delivery_project.domain.entity.user.User
import com.example.delivery_project.enums.Role
import com.example.delivery_project.security.auth.CustomUserDetails
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.test.util.ReflectionTestUtils
import java.time.Duration
import java.util.Base64

class TokenProviderTest {
    private lateinit var tokenProvider: TokenProvider

    @BeforeEach
    fun setUp() {
        val properties = JwtProperties().apply {
            issuer = "delivery-insight-test"
            secretKey = Base64.getEncoder().encodeToString(ByteArray(64))
        }
        tokenProvider = TokenProvider(properties)
        ReflectionTestUtils.invokeMethod<Unit>(tokenProvider, "init")
    }

    @Test
    fun `JWT를 생성하고 사용자 정보와 인증객체를 복구한다`() {
        val user = user()
        val token = tokenProvider.generateToken(user, Duration.ofMinutes(10))

        val restored = tokenProvider.getTokenDetails(token)
        val authentication = tokenProvider.getAuthentication(restored, token)

        assertThat(tokenProvider.validateToken(token)).isEqualTo(TokenStatus.VALID)
        assertThat(restored.id).isEqualTo(user.id)
        assertThat(restored.loginId).isEqualTo(user.loginId)
        assertThat(restored.name).isEqualTo(user.name)
        assertThat(restored.role).isEqualTo(user.role)
        assertThat(authentication.principal).isInstanceOf(CustomUserDetails::class.java)
        assertThat(authentication.credentials).isEqualTo(token)
        assertThat(authentication.authorities.map { it.authority })
            .containsExactly(Role.ROLE_DELIVERY_DRIVER.name)
    }

    @Test
    fun `만료된 JWT를 구분한다`() {
        val token = tokenProvider.generateToken(user(), Duration.ofSeconds(-1))

        assertThat(tokenProvider.validateToken(token)).isEqualTo(TokenStatus.EXPIRED)
    }

    @Test
    fun `위조되거나 형식이 잘못된 JWT를 구분한다`() {
        assertThat(tokenProvider.validateToken("not-a-jwt")).isEqualTo(TokenStatus.INVALID)
    }

    private fun user(): User = User(
        id = 10L,
        loginId = "driver",
        password = "encoded-password",
        name = "배송기사",
        role = Role.ROLE_DELIVERY_DRIVER,
    )
}
