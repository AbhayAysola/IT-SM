package com.itsm.user

import com.itsm.admin.technician.TechnicianDirectoryReader
import com.itsm.common.model.Role
import com.itsm.common.spi.TechnicianSummary
import com.itsm.common.spi.UserDirectory
import com.itsm.common.spi.UserSummary
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * P1's [UserDirectory]. Returns immutable shared summaries only; no entity leaves this package.
 *
 * User methods read `users`. Technician methods delegate to the technician module's read port ([TechnicianDirectoryReader]),
 * so this class never touches technician tables or entities. Semantics (see docs/architecture/integration-guide.md):
 *  - [findTechnician]: the USABLE technician (active profile + active user + role TECHNICIAN) or null. Null means "no usable
 *    technician with this id", not "no such user": use [findById] for the account itself.
 *  - [listAvailableTechnicians]: usable technicians whose administrator-set `available` flag is true (and, if asked, with a
 *    skill for that active category). It knows nothing about workload: P3 must apply workload and assignment policy.
 *  - [escalationChain]: the reporting line above a technician, nearest first, skipping inactive managers.
 */
@Component
class JpaUserDirectory(
    private val users: UserRepository,
    private val technicians: TechnicianDirectoryReader
) : UserDirectory {

    @Transactional(readOnly = true)
    override fun findById(userId: Long): UserSummary? = users.findById(userId).orElse(null)?.toSummaryOrNull()

    override fun findTechnician(userId: Long): TechnicianSummary? = technicians.find(userId)

    override fun listAvailableTechnicians(categoryId: Long?): List<TechnicianSummary> = technicians.listAvailable(categoryId)

    override fun escalationChain(technicianId: Long): List<UserSummary> = technicians.escalationChain(technicianId)

    @Transactional(readOnly = true)
    override fun listAdmins(): List<UserSummary> =
        users.findByRoleAndActiveTrueOrderByIdAsc(Role.ADMIN.name).mapNotNull { it.toSummaryOrNull() }

    /** A row with a corrupt role is skipped rather than failing every consumer. */
    private fun UserEntity.toSummaryOrNull(): UserSummary? {
        val role = try { Role.fromStored(role) } catch (e: IllegalStateException) { return null }
        return UserSummary(id = id, email = email, name = name, role = role, active = active)
    }
}
