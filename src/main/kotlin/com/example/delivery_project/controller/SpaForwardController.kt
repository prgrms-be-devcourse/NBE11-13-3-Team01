package com.example.delivery_project.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class SpaForwardController {
    @GetMapping("/", "/login", "/plans", "/plans/new", "/plans/{planId:\\d+}")
    fun forwardToReact(): String = "forward:/index.html"
}
