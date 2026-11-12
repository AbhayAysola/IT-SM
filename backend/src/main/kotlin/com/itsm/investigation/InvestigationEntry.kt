package com.itsm.investigation

import com.itsm.common.security.Permission
import com.itsm.lifecycle.IncidentState
import com.itsm.lifecycle.IncidentState.*
import java.time.Instant

/** Who can read an entry. */
enum class Visibility {
    /** Technicians and admins only: working notes, hypotheses, anything not meant for the reporter. */
    INTERNAL,
    /** Also the reporter. Write these in plain language: they are read by non-specialists. */
    REPORTER_VISIBLE
}

/**
 * The kinds of entry in an incident's investigation timeline.
 *
 * Only [manual] types can be added directly. The others are produced as a side effect of a lifecycle transition
 * (see [InvestigationPolicy.entryFor]), so an `INFO_REQUEST` can never exist without the incident really being in
 * `PENDING_INFO`, and the timeline and the incident state cannot disagree.
 *
 * @property permission what the author must hold (ownership is the service's check, as for transitions)
 * @property defaultVisibility used when the author does not choose
 * @property statesAllowed for manual types: states in which a technician may add one. The technician owns the
 *   incident from ACKNOWLEDGED; RESOLVED is included so a fix can be documented while it awaits verification.
 */
enum class InvestigationEntryType(
    val permission: Permission,
    val manual: Boolean,
    val defaultVisibility: Visibility,
    val statesAllowed: Set<IncidentState> = emptySet()
) {
    /** Free-form working note. */
    NOTE(Permission.INCIDENT_WORK, true, Visibility.INTERNAL, WORK_STATES),
    /** Something established: a log line, a root cause, a reproduced error. */
    FINDING(Permission.INCIDENT_WORK, true, Visibility.INTERNAL, WORK_STATES),
    /** Something done to the system: restarted a service, cleared a cache, replaced a part. */
    ACTION_TAKEN(Permission.INCIDENT_WORK, true, Visibility.INTERNAL, WORK_STATES),

    /** The technician asks the reporter a question (transition `REQUEST_INFO`). */
    INFO_REQUEST(Permission.INCIDENT_WORK, false, Visibility.REPORTER_VISIBLE),
    /** The reporter answers (transition `PROVIDE_INFO`). */
    INFO_RESPONSE(Permission.INCIDENT_VIEW_OWN, false, Visibility.REPORTER_VISIBLE),
    /** How it was fixed (transition `RESOLVE`). The reporter reads this when deciding to verify. */
    RESOLUTION(Permission.INCIDENT_WORK, false, Visibility.REPORTER_VISIBLE),
    /** Why the fix did not work (transition `REOPEN`). */
    REOPEN_REASON(Permission.INCIDENT_VERIFY_OWN, false, Visibility.REPORTER_VISIBLE),
    /** Why the incident moved to another technician (transition `REASSIGN`). Never shown to the reporter. */
    REASSIGNMENT(Permission.INCIDENT_ASSIGN, false, Visibility.INTERNAL);

    /** Audit action to publish when an entry of this type is added. Metadata must carry the type, never the text. */
    val auditAction: String get() = "INCIDENT_INVESTIGATION_ENTRY_ADDED"
}

private val WORK_STATES: Set<IncidentState> = setOf(ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO, RESOLVED)

/**
 * One immutable line of the timeline. Entries are append-only: there is no edit and no delete anywhere
 * (see [InvestigationLog]). A mistake is corrected by a new entry.
 *
 * Construct through [InvestigationPolicy], which trims and validates with client-facing errors. The checks here
 * only guard the invariants against programming mistakes.
 */
data class InvestigationEntry(
    val incidentId: String,
    val authorId: Long,
    val type: InvestigationEntryType,
    val visibility: Visibility,
    val body: String,
    /** The incident's state at the moment of writing, so the timeline reads correctly after later moves. */
    val incidentState: IncidentState,
    val createdAt: Instant
) {
    init {
        require(ID_FORMAT.matches(incidentId)) { "invalid incident id" }
        require(authorId > 0) { "authorId must be positive" }
        require(body.isNotBlank() && body.length <= MAX_BODY_LENGTH) { "body must be 1..$MAX_BODY_LENGTH characters" }
        // A reporter must always be able to read what concerns them; an internal-only question would hang the incident.
        require(type.defaultVisibility == Visibility.INTERNAL || visibility == Visibility.REPORTER_VISIBLE) {
            "$type must be visible to the reporter"
        }
        // The mirror image: reassignment notes may name colleagues and must not leak to the reporter.
        require(type != InvestigationEntryType.REASSIGNMENT || visibility == Visibility.INTERNAL) {
            "REASSIGNMENT must stay internal"
        }
    }

    companion object {
        const val MAX_BODY_LENGTH = 4000
        val ID_FORMAT = Regex("^[A-Za-z0-9_-]{1,30}$")
    }
}
