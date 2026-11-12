package com.itsm.admin.technician

import com.itsm.common.spi.TechnicianSummary
import com.itsm.common.spi.UserSummary
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * The technician module's read port for `UserDirectory` (implemented in the `user` module by `JpaUserDirectory`, which does
 * not see this module's entities, repository or SQL). Returns shared immutable SPI types only.
 */
interface TechnicianDirectoryReader {
    /** The usable technician (active profile, active user, role TECHNICIAN), else null. */
    fun find(userId: Long): TechnicianSummary?

    /** Usable technicians flagged `available` by an administrator, optionally with an active-category skill. Workload is NOT considered. */
    fun listAvailable(categoryId: Long?): List<TechnicianSummary>

    fun escalationChain(technicianId: Long): List<UserSummary>
}

@Component
class JdbcTechnicianDirectoryReader(private val queries: TechnicianQueries) : TechnicianDirectoryReader {

    @Transactional(readOnly = true)
    override fun find(userId: Long): TechnicianSummary? = queries.summaries(userId, onlyAvailable = false, categoryId = null).firstOrNull()

    @Transactional(readOnly = true)
    override fun listAvailable(categoryId: Long?): List<TechnicianSummary> = queries.summaries(null, onlyAvailable = true, categoryId = categoryId)

    @Transactional(readOnly = true)
    override fun escalationChain(technicianId: Long): List<UserSummary> = queries.escalationChain(technicianId)
}
