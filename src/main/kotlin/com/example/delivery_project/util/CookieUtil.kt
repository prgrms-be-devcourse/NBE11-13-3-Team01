package com.example.delivery_project.util

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

object CookieUtil {
    const val REFRESH_TOKEN_COOKIE = "refreshToken"

    fun addCookie(response: HttpServletResponse, name: String, value: String, maxAge: Int) {
        val cookie = Cookie(name, value).apply {
            isHttpOnly = true
            // 현재 프로젝트는 로컬 환경에서 실행한다.
            secure = false
            path = "/"
            this.maxAge = maxAge
        }
        response.addCookie(cookie)
    }

    fun deleteCookie(request: HttpServletRequest, response: HttpServletResponse, name: String) {
        request.cookies.orEmpty()
            .filter { it.name == name }
            .forEach { cookie ->
                cookie.maxAge = 0
                cookie.path = "/"
                cookie.value = ""
                response.addCookie(cookie)
            }
    }
}
