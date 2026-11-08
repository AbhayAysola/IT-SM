package com.itsm.auth

import com.itsm.auth.dto.AuthResponse
import com.itsm.auth.dto.LoginRequest
import com.itsm.auth.dto.RegisterRequest
import com.itsm.common.error.DuplicateResource
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import com.itsm.user.toDto
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val authManager: AuthenticationManager
) {
    fun register(req: RegisterRequest): AuthResponse {
        if (userRepository.findByEmail(req.email) != null)
            throw DuplicateResource("Email is already registered")
        val user = userRepository.save(
            UserEntity(email = req.email, password = passwordEncoder.encode(req.password), name = req.name)
        )
        val token = jwtService.generateToken(user.email)
        return AuthResponse(token = token, user = user.toDto())
    }

    fun login(req: LoginRequest): AuthResponse {
        authManager.authenticate(UsernamePasswordAuthenticationToken(req.email, req.password))
        val user = userRepository.findByEmail(req.email)!!
        val token = jwtService.generateToken(user.email)
        return AuthResponse(token = token, user = user.toDto())
    }
}
