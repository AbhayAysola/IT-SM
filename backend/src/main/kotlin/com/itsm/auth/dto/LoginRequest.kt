package com.itsm.auth.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class LoginRequest(
    @field:NotBlank @field:Size(max = 254)
    val email: String? = null,
    @field:NotBlank @field:Size(max = 128)
    val password: String? = null
)
