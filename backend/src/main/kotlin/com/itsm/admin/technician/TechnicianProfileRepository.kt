package com.itsm.admin.technician

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TechnicianProfileRepository : JpaRepository<TechnicianProfileEntity, Long> {
    /** Profiles (any state) whose reporting line points at [managerUserId]. */
    fun countByReportsToUserId(managerUserId: Long): Long

    fun countByReportsToUserIdAndActiveTrue(managerUserId: Long): Long
}
