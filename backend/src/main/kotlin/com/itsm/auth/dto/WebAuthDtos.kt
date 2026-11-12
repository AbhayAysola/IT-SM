package com.itsm.auth.dto

import com.itsm.user.UserDto

/** Browser (`X-ITSM-Client: web`) login response. The JWT travels ONLY in the HttpOnly cookie, never in this body. */
data class WebLoginResponse(val user: UserDto = UserDto())

/** The one body `POST /api/auth/logout` returns. */
data class LogoutResponse(val status: String = "LOGGED_OUT")
