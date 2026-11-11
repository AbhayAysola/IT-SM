package com.itsm.auth

import com.itsm.auth.dto.AuthResponse
import com.itsm.auth.dto.LoginRequest
import com.itsm.auth.dto.RegisterRequest
import com.itsm.auth.dto.RegistrationResponse
import com.itsm.auth.ratelimit.AuthAttemptLimiter
import com.itsm.common.error.AuthenticationFailed
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.ItsmException
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
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.slf4j.LoggerFactory

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtService: JwtService,
    private val authManager: AuthenticationManager,
    private val audit: AuditPublisher,
    private val limiter: AuthAttemptLimiter,
    transactionManager: PlatformTransactionManager,
    @Value("\${app.registration.enabled:false}") private val registrationEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)
    private val tx = TransactionTemplate(transactionManager)

    /**
     * Public self-registration (B14a). Disabled unless `app.registration.enabled` is true.
     *
     * Every well-formed request gets the SAME answer, [RegistrationResponse], whether the email is new or already taken, so the
     * response does not reveal whether an account exists. A new address creates an EMPLOYEE (whatever the client sent); an
     * existing one is left completely untouched (no password, role or state change, no audit event). The password is hashed on
     * BOTH paths so the dominant cost, BCrypt, is the same. Malformed input is still a 400, which does not depend on
     * whether the email exists.
     *
     * Limits of this protection (documented in security.md): without email verification a registrant can still sign in right
     * after registering, so someone who registers an address and then logs in with the same password can tell whether it was new.
     * That residual signal costs an account per probe and is throttled, but only email verification removes it.
     */
    fun register(req: RegisterRequest): RegistrationResponse {
        if (!registrationEnabled) throw ItsmException(ErrorCode.RESOURCE_NOT_FOUND, "Self-registration is not available")
        val email = EmailNormalizer.normalize(req.email!!)
        PasswordRules.require(req.password!!)
        val hash = passwordEncoder.encode(req.password)     // same work whether or not the address is taken
        if (userRepository.existsByEmail(email)) {
            log.debug("registration for an existing address ignored")
            return RegistrationResponse()
        }
        try {
            tx.executeWithoutResult { createEmployee(email, hash, req.name!!.trim()) }
        } catch (e: DataIntegrityViolationException) {
            // A concurrent registration won the unique index. The transaction is already rolled back (we catch OUTSIDE it).
            if (!userRepository.existsByEmail(email)) throw e
            log.debug("concurrent registration for the same address ignored")
        }
        return RegistrationResponse()
    }

    private fun createEmployee(email: String, hash: String, name: String) {
        val user = userRepository.saveAndFlush(UserEntity(email = email, password = hash, name = name, role = Role.EMPLOYEE.name))
        audit.publish(
            AuditableEvent(
                actor = AuditActor.UserActor(user.id, user.email, Role.EMPLOYEE),
                action = "USER_REGISTERED", entityType = "USER", entityId = user.id.toString(),
                newState = Role.EMPLOYEE.name, description = "User self-registered",
                metadata = mapOf("role" to Role.EMPLOYEE.name)
            )
        )
    }

    /**
     * Every credential failure (unknown email, wrong password, inactive account, over-long password) answers the same 401, so
     * the endpoint does not reveal which accounts exist or are disabled. Throttled BEFORE any password work ([clientIp] is
     * resolved by `ClientIpResolver`): a locked-out caller gets 429 even with the correct password.
     */
    fun login(req: LoginRequest, clientIp: String): AuthResponse {
        val email = EmailNormalizer.normalize(req.email!!)
        val ticket = limiter.beforeLogin(clientIp, email)
        try {
            val password = req.password!!
            if (PasswordRules.tooLongForBcrypt(password)) throw AuthenticationFailed.invalidCredentials()
            try {
                authManager.authenticate(UsernamePasswordAuthenticationToken(email, password))
            } catch (e: AuthenticationException) {
                throw AuthenticationFailed.invalidCredentials()
            }
            val user = userRepository.findByEmail(email) ?: throw AuthenticationFailed.invalidCredentials()
            limiter.loginSucceeded(ticket)
            return AuthResponse(token = jwtService.generateToken(user.id, user.tokenVersion), user = user.toDto())
        } catch (e: AuthenticationFailed) {
            throw e                                  // a credential failure stays counted
        } catch (e: RuntimeException) {
            limiter.loginNotCounted(ticket)          // an outage is not the caller's failed guess
            throw e
        }
    }
}
