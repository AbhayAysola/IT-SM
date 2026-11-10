package com.itsm.auth.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** Public registration. There is deliberately NO role field: unknown JSON properties (e.g. `role`) are ignored. */
data class RegisterRequest(
    @field:NotBlank @field:Email @field:Size(max = 254)
    val email: String? = null,
    @field:NotBlank @field:Size(min = 8, max = 128)
    val password: String? = null,
    @field:NotBlank @field:Size(max = 100)
    val name: String? = null
)
