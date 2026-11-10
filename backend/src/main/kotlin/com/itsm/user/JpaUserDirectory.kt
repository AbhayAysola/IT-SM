package com.itsm.user

import com.itsm.common.model.Role
import com.itsm.common.spi.TechnicianSummary
import com.itsm.common.spi.UserDirectory
import com.itsm.common.spi.UserSummary
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P1's [UserDirectory]. Returns immutable [UserSummary] values only; the entity never leaves this package.
 *
 * Technician profiles (availability, skills, workload limits, reporting lines) are persisted by B6, which does not exist
 * yet. Until then the technician-profile methods answer "no profile data": [findTechnician] is null and the list /
 * chain methods are empty. They must NOT be read as "there are no technicians". B6 replaces these three bodies.
 *
 * The SPI has no way to say "not available", so the distinction is explicit here instead: [TECHNICIAN_PROFILES_AVAILABLE]
 * is false until B6 flips it, and the first call to a technician method logs a WARN. P3 must treat an empty technician
 * list as "cannot assign" (never as a genuine empty directory) while the flag is false.
 */
@Component
class JpaUserDirectory(private val users: UserRepository) : UserDirectory {

    @Transactional(readOnly = true)
    override fun findById(userId: Long): UserSummary? = users.findById(userId).orElse(null)?.toSummaryOrNull()

    override fun findTechnician(userId: Long): TechnicianSummary? { warnOnce(); return null }            // B6

    override fun listAvailableTechnicians(categoryId: Long?): List<TechnicianSummary> { warnOnce(); return emptyList() }   // B6

    override fun escalationChain(technicianId: Long): List<UserSummary> { warnOnce(); return emptyList() }   // B6

    private fun warnOnce() {
        if (!TECHNICIAN_PROFILES_AVAILABLE && warned.compareAndSet(false, true)) {
            log.warn("UserDirectory technician methods were called but technician profiles are not implemented yet (B6): results are empty by absence of data, not by fact.")
        }
    }

    @Transactional(readOnly = true)
    override fun listAdmins(): List<UserSummary> =
        users.findByRoleAndActiveTrueOrderByIdAsc(Role.ADMIN.name).mapNotNull { it.toSummaryOrNull() }

    private val log = LoggerFactory.getLogger(JpaUserDirectory::class.java)
    private val warned = AtomicBoolean(false)

    companion object {
        /** False until B6 persists technician profiles. While false, empty/null technician answers mean "no data". */
        const val TECHNICIAN_PROFILES_AVAILABLE = false
    }

    /** A row with a corrupt role is skipped rather than failing every consumer. */
    private fun UserEntity.toSummaryOrNull(): UserSummary? {
        val role = try { Role.fromStored(role) } catch (e: IllegalStateException) { return null }
        return UserSummary(id = id, email = email, name = name, role = role, active = active)
    }
}
