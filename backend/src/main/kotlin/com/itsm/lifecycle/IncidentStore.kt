package com.itsm.lifecycle

import com.itsm.common.model.Priority
import java.time.Instant

/**
 * P3's view of one row of `incidents` (V20): the fields the lifecycle reads and changes. Intake data (title,
 * description) is P2's and is not here. Plain values, so the services are testable without a database.
 */
data class IncidentRecord(
    val id: String,
    val state: IncidentState,
    val reporterId: Long,
    val assigneeId: Long?,
    val categoryId: Long?,
    val serviceId: Long?,
    val priority: Priority?,
    val assignedAt: Instant?,
    val firstResponseAt: Instant?,
    val resolvedAt: Instant?,
    val closedAt: Instant?
)

/** A resolved incident offered as precedent for a similar one. */
data class ResolvedIncident(
    val incidentId: String,
    val title: String,
    /** The `RESOLUTION` entry written when the technician resolved it. */
    val resolution: String,
    val resolvedAt: Instant,
    val assigneeId: Long,
    val sameService: Boolean
)

/**
 * Storage for the incident fields P3 owns the lifecycle of. Creating an incident is P2's job, so there is no
 * `create` here (tests insert rows directly).
 */
interface IncidentStore {
    fun find(incidentId: String): IncidentRecord?

    /** Loads the row with a write lock (`MANDATORY` transaction), so concurrent moves of one incident queue up. */
    fun lock(incidentId: String): IncidentRecord?

    /** Writes the mutable fields of an incident previously returned by [lock] (`MANDATORY` transaction). */
    fun save(record: IncidentRecord)

    /** Open incidents per technician (states with `countsTowardWorkload`), every requested id present, zero if none. */
    fun activeCounts(technicianIds: Collection<Long>): Map<Long, Int>

    /** The latest `assigned_at` per technician; technicians never assigned are absent. */
    fun lastAssignedAt(technicianIds: Collection<Long>): Map<Long, Instant>

    /**
     * Serialises concurrent assignments to one technician until the transaction ends, so two incidents cannot both
     * pass the capacity check for their last free slot (`MANDATORY` transaction).
     */
    fun lockTechnician(technicianId: Long)

    /** Incidents in [categoryId] that have a recorded fix, same service first, then newest. */
    fun similarResolved(categoryId: Long, serviceId: Long?, excludeIncidentId: String, limit: Int): List<ResolvedIncident>
}
