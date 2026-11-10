package com.itsm.bootstrap

import com.itsm.admin.support.AdvisoryLocks
import com.itsm.auth.PasswordRules
import com.itsm.common.error.InvalidInput
import com.itsm.common.event.AuditActor
import com.itsm.common.event.AuditableEvent
import com.itsm.common.model.Role
import com.itsm.common.spi.AuditPublisher
import com.itsm.user.EmailNormalizer
import com.itsm.user.UserEntity
import com.itsm.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A bootstrap precondition failed. The message is safe to print (never contains credentials). */
class BootstrapException(message: String) : RuntimeException(message)

/**
 * Creates the FIRST administrator. Refuses when any ADMIN row already exists. The check and the insert run under a
 * transaction-scoped advisory lock shared with every other admin-set mutation, so concurrent invocations (two pods, a
 * double run) serialise: exactly one creates the admin, the others fail with "already exists".
 */
@Service
class AdminBootstrapService(
    private val users: UserRepository,
    private val encoder: PasswordEncoder,
    private val audit: AuditPublisher,
    private val locks: AdvisoryLocks
) {
    @Transactional
    fun bootstrap(rawEmail: String, password: String, rawName: String): Long {
        val email = EmailNormalizer.normalize(rawEmail)
        val name = rawName.trim()
        if (!EMAIL.matches(email) || email.length > 254) throw BootstrapException("The bootstrap email is not a valid email address.")
        if (name.isEmpty() || name.length > 100) throw BootstrapException("The bootstrap name must be 1..100 characters.")
        try {
            PasswordRules.require(password)
        } catch (e: InvalidInput) {
            throw BootstrapException("The bootstrap password is invalid: ${e.fieldErrors.joinToString("; ") { it.message }}.")
        }

        locks.lock(AdvisoryLocks.ADMIN_ACCOUNTS)
        if (users.existsByRole(Role.ADMIN.name)) throw BootstrapException("An administrator already exists; bootstrap refused.")
        if (users.existsByEmail(email)) {
            throw BootstrapException("An account with the bootstrap email already exists; it is not promoted automatically.")
        }
        val admin = users.saveAndFlush(UserEntity(email = email, password = encoder.encode(password), name = name, role = Role.ADMIN.name))
        audit.publish(
            AuditableEvent(
                actor = AuditActor.system("ADMIN_BOOTSTRAP"), action = "USER_ADMIN_BOOTSTRAPPED", entityType = "USER",
                entityId = admin.id.toString(), newState = Role.ADMIN.name, description = "Created the first administrator",
                metadata = mapOf("role" to Role.ADMIN.name, "targetUserId" to admin.id)
            )
        )
        return admin.id
    }

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}

/**
 * Command mode: runs ONLY when the process is started with `--itsm.bootstrap-admin=true` (see docs/architecture/security.md).
 * It creates the first ADMIN from environment variables, then exits: 0 on success, 2 for missing configuration, 1 when the
 * bootstrap is refused or fails. It exposes no HTTP endpoint and never prints credentials or hashes.
 */
@Component
@ConditionalOnProperty(name = ["itsm.bootstrap-admin"], havingValue = "true")
class AdminBootstrapRunner(
    private val service: AdminBootstrapService,
    private val context: ConfigurableApplicationContext,
    @Value("\${itsm.bootstrap.admin.email:}") private val email: String,
    @Value("\${itsm.bootstrap.admin.password:}") private val password: String,
    @Value("\${itsm.bootstrap.admin.name:Administrator}") private val name: String
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(AdminBootstrapRunner::class.java)

    /** Testable core: returns the process exit code instead of exiting. */
    fun execute(): Int {
        val missing = buildList {
            if (email.isBlank()) add("ITSM_BOOTSTRAP_ADMIN_EMAIL")
            if (password.isBlank()) add("ITSM_BOOTSTRAP_ADMIN_PASSWORD")
        }
        if (missing.isNotEmpty()) {
            log.error("Admin bootstrap not run: missing required environment variable(s): {}", missing.joinToString(", "))
            return EXIT_MISSING_CONFIG
        }
        return try {
            val id = service.bootstrap(email, password, name)
            log.info("Admin bootstrap complete: created administrator with id {}", id)
            0
        } catch (e: BootstrapException) {
            log.error("Admin bootstrap refused: {}", e.message)
            EXIT_FAILED
        } catch (e: Exception) {
            log.error("Admin bootstrap failed ({})", e.javaClass.simpleName)   // class only: never echo message/params
            EXIT_FAILED
        }
    }

    override fun run(args: ApplicationArguments) {
        val code = execute()
        System.exit(SpringApplication.exit(context, { code }))
    }

    companion object {
        const val EXIT_FAILED = 1
        const val EXIT_MISSING_CONFIG = 2
    }
}
