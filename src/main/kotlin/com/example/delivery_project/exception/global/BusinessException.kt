package com.example.delivery_project.exception.global

import com.example.delivery_project.exception.ErrorCode

class BusinessException(
    val errorCode: ErrorCode,
    val reason: String? = null,
) : RuntimeException(errorCode.message)
