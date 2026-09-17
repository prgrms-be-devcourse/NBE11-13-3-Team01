package com.example.delivery_project.security.token

import org.springframework.stereotype.Component
import java.security.MessageDigest

@Component
class RefreshTokenHasher {

    // DB + Redis에 저장할 refresh token 문자열 해싱
    fun hash(token: String): String {
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") { "%02x".format(it) }
    }
}