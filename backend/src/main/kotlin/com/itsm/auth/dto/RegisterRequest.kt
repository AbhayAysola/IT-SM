package com.itsm.auth.dto

data class RegisterRequest(
    val email: String = "",
    val password: String = "",
    val name: String = ""
)
