package com.itsm.auth

import com.itsm.auth.dto.AuthResponse
import com.itsm.auth.dto.LoginRequest
import com.itsm.auth.dto.RegisterRequest
import com.itsm.common.error.AuthenticationFailed
import com.itsm.common.error.DuplicateResource
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.model.Role
import com.itsm.common.spi.AuditPublisher
import com.itsm.user.EmailNormalizer
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import com.itsm.user.toDto
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val authManager: AuthenticationManager,
    private val audit: AuditPublisher
) {
    /** Always creates an EMPLOYEE, whatever the client sent. Elevated roles are only created via bootstrap or the admin API. */
    @Transactional
    fun register(req: RegisterRequest): AuthResponse {
        val email = EmailNormalizer.normalize(req.email!!)
        PasswordRules.require(req.password!!)
        if (userRepository.existsByEmail(email)) throw DuplicateResource("Email is already registered")
        val user = userRepository.saveAndFlush(
            UserEntity(email = email, password = passwordEncoder.encode(req.password), name = req.name!!.trim(), role = Role.EMPLOYEE.name)
        )
        audit.publish(
            AuditableEvent(
                actor = AuditActor.UserActor(user.id, user.email, Role.EMPLOYEE),
                action = "USER_REGISTERED", entityType = "USER", entityId = user.id.toString(),
                newState = Role.EMPLOYEE.name, description = "User self-registered",
                metadata = mapOf("role" to Role.EMPLOYEE.name)
            )
        )
        return AuthResponse(token = jwtService.generateToken(user.id, user.tokenVersion), user = user.toDto())
    }

    /**
     * Every failure (unknown email, wrong password, inactive account, over-long password) answers the same 401, so the
     * endpoint does not reveal which accounts exist or are disabled.
     */
    fun login(req: LoginRequest): AuthResponse {
        val email = EmailNormalizer.normalize(req.email!!)
        val password = req.password!!
        if (PasswordRules.tooLongForBcrypt(password)) throw AuthenticationFailed.invalidCredentials()
        try {
            authManager.authenticate(UsernamePasswordAuthenticationToken(email, password))
        } catch (e: AuthenticationException) {
            throw AuthenticationFailed.invalidCredentials()
        }
        val user = userRepository.findByEmail(email) ?: throw AuthenticationFailed.invalidCredentials()
        return AuthResponse(token = jwtService.generateToken(user.id, user.tokenVersion), user = user.toDto())
    }
}
