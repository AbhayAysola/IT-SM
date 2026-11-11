package com.itsm.lifecycle

import com.itsm.common.model.Priority
import java.time.Instant

/**
 * P3's view of one row of `incidents` (V20): the fields the lifecycle reads and changes. Intake data (title,
 * description, created) is P2's: it is carried for display only and never written back. Plain values, so the services are testable without a database.
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
    val closedAt: Instant?,
    /** Intake fields, read-only here (P2 writes them); defaults keep tests and callers that do not care short. */
    val title: String = "",
    val description: String = "",
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH
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

/** Whose incidents a list covers: everyone's for an admin, otherwise those the user reported or is assigned. */
data class IncidentScope(val userId: Long, val all: Boolean)

data class IncidentListQuery(
    val scope: IncidentScope,
    /** Empty = every state. */
    val states: Set<IncidentState>,
    /** Matches the id or the title, case-insensitively. Null or blank = no filter. */
    val search: String?,
    val offset: Long,
    val limit: Int
)

data class IncidentListRow(val record: IncidentRecord, val hasFeedback: Boolean)
data class IncidentPage(val rows: List<IncidentListRow>, val total: Long)

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

    /** One page, most urgent first (priority, then most recently updated), and the total matching. */
    fun list(query: IncidentListQuery): IncidentPage

    /** How many incidents the scope holds per state, ignoring any state filter or search (drives tab counts). */
    fun stateCounts(scope: IncidentScope): Map<IncidentState, Int>

    /** Incidents in [categoryId] that have a recorded fix, same service first, then newest. */
    fun similarResolved(categoryId: Long, serviceId: Long?, excludeIncidentId: String, limit: Int): List<ResolvedIncident>
}
