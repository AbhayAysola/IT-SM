package com.itsm.auth.dto

data class LoginRequest(
    val email: String = "",
    val password: String = ""
)
