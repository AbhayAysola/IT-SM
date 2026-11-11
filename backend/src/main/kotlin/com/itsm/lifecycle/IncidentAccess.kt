package com.itsm.lifecycle

import com.itsm.common.error.ResourceNotFound
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.security.CurrentUser
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Who may open an incident, and the ownership checks the permission table cannot express. Mirrors the rule P2's
 * `IncidentAccessPolicy` is specified to have (reporter, assigned technician, admin); when P2 provides that bean,
 * these two functions are the only places to switch over.
 */
internal object IncidentAccess {
    /** The clock's instant at the precision PostgreSQL stores (microseconds), so what we return equals what we saved. */
    fun now(clock: Clock): Instant = Instant.now(clock).truncatedTo(ChronoUnit.MICROS)

    fun canView(record: IncidentRecord, user: CurrentUser): Boolean =
        user.isAdmin || record.reporterId == user.id || record.assigneeId == user.id

    /** An incident the caller may not see does not exist, as far as they can tell (404, never 403). */
    fun requireVisible(record: IncidentRecord?, incidentId: String, user: CurrentUser): IncidentRecord {
        if (record == null || !canView(record, user)) throw ResourceNotFound("Incident", incidentId)
        return record
    }

    fun requireAssignee(record: IncidentRecord, user: CurrentUser) {
        if (!user.isAdmin && record.assigneeId != user.id) throw UnauthorizedOperation("Only the assigned technician can do this")
    }

    fun requireReporter(record: IncidentRecord, user: CurrentUser) {
        if (!user.isAdmin && record.reporterId != user.id) throw UnauthorizedOperation("Only the person who reported the incident can do this")
    }
}
