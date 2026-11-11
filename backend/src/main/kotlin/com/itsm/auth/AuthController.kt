package com.itsm.auth

import com.itsm.auth.dto.AuthResponse
import com.itsm.auth.dto.LoginRequest
import com.itsm.auth.dto.RegisterRequest
import com.itsm.auth.dto.RegistrationResponse
import com.itsm.auth.ratelimit.ClientIpResolver
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService, private val clientIps: ClientIpResolver) {

    /** 202 with the same body for a new and an already-registered address; never a token. See [AuthService.register]. */
    @PostMapping("/register")
    fun register(@Valid @RequestBody req: RegisterRequest): ResponseEntity<RegistrationResponse> =
        ResponseEntity.accepted().body(authService.register(req))

    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest, http: HttpServletRequest): ResponseEntity<AuthResponse> =
        ResponseEntity.ok(authService.login(req, clientIps.resolve(http)))
}
