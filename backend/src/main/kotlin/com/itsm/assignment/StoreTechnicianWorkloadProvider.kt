package com.itsm.assignment

import com.itsm.common.spi.TechnicianWorkloadProvider
import com.itsm.lifecycle.IncidentStore
import org.springframework.stereotype.Component

/**
 * The SPI P1 and P4 read workload through. "Active" means the states with `IncidentState.countsTowardWorkload`
 * (assigned up to pending info; a resolved incident no longer weighs on the technician). One query for any number
 * of technicians; every requested id is present in the answer, with zero when they have nothing open.
 */
@Component
class StoreTechnicianWorkloadProvider(private val incidents: IncidentStore) : TechnicianWorkloadProvider {
    override fun activeIncidentCount(technicianId: Long): Int = activeIncidentCounts(listOf(technicianId)).getValue(technicianId)

    override fun activeIncidentCounts(technicianIds: Collection<Long>): Map<Long, Int> = incidents.activeCounts(technicianIds)
}
