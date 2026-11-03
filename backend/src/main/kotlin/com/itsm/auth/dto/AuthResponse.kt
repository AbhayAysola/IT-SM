package com.itsm.auth.dto

import com.itsm.user.UserDto

data class AuthResponse(
    val token: String = "",
    val user: UserDto = UserDto()
)
