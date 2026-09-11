package com.example.delivery_project.controller

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SpaForwardControllerTest {
    private val controller = SpaForwardController()

    @Test
    fun `React 화면 경로를 index html로 포워딩한다`() {
        assertThat(controller.forwardToReact()).isEqualTo("forward:/index.html")
    }
}
