package com.itsm.sla

import com.itsm.common.model.Priority
import java.time.Instant

/** The two SLA clocks of an incident (docs/architecture/p4.md section 2). */
enum class SlaClock { RESPONSE, RESOLUTION }

/**
 * Status of one clock. `MET` and `BREACHED` are final for the response clock; the resolution clock can leave `MET` when
 * the incident is reopened. A breach is never undone: a breached clock stays `BREACHED` even after it stops.
 */
enum class ClockStatus { NO_POLICY, RUNNING, WARNING, PAUSED, MET, BREACHED }

/** Overall status of an incident's SLA: the most serious of its two clocks, in this order. */
enum class SlaStatus { NO_POLICY, BREACHED, WARNING, PAUSED, RUNNING, MET }

/** Why no targets apply. Stored, so the values are a contract with the `incident_sla` CHECK constraint. */
enum class NoPolicyReason { MISSING_SLA_CONFIG, SERVICE_NOT_FOUND }

/** Where the targets came from (`SlaPolicyView.serviceId == null` means the global default). */
enum class PolicyScope { SERVICE, GLOBAL }

/** What P4 needs from an incident row. Read through [IncidentSnapshotReader], the only P4 code that knows that table. */
data class IncidentSnapshot(
    val incidentId: String,
    val state: String,
    val priority: Priority,
    val serviceId: Long,
    val createdAt: Instant,
    /** Set by every lifecycle transition; used as the best estimate of when a pause began or ended (see [SlaEvaluator]). */
    val updatedAt: Instant,
    val firstResponseAt: Instant?,
    val resolvedAt: Instant?
) {
    val waitingOnReporter: Boolean get() = state == PENDING_INFO
    val trackingFinished: Boolean get() = state in FINISHED_STATES

    companion object {
        const val PENDING_INFO = "PENDING_INFO"
        /** After VERIFIED nothing can reopen the incident, so its SLA is final. */
        val FINISHED_STATES = setOf("VERIFIED", "CLOSED")
    }
}

/** Targets resolved from P1's `SlaPolicyProvider`. */
data class SlaTargets(
    val policyId: Long,
    val scope: PolicyScope,
    val responseMinutes: Int,
    val resolutionMinutes: Int,
    val warningThresholdPct: Int
)

/** The answer of one policy lookup. */
sealed interface TargetLookup {
    data class Found(val targets: SlaTargets) : TargetLookup
    data class Missing(val reason: NoPolicyReason) : TargetLookup
}

/**
 * One row of `incident_sla`: the facts recorded so far (when each clock stopped, pauses, breaches) and the values
 * derived from them at the last evaluation (deadlines and statuses). Immutable; [SlaEvaluator] returns a new copy.
 */
data class SlaRecord(
    val incidentId: String,
    /** The priority and service the targets were resolved for. */
    val priority: Priority,
    val serviceId: Long,
    /** When both clocks started: the incident's `created_at`. */
    val clockStart: Instant,
    val targets: SlaTargets? = null,
    /** Set exactly when [targets] is null. */
    val noPolicyReason: NoPolicyReason? = null,

    val responseDueAt: Instant? = null,
    val responseWarningAt: Instant? = null,
    val responseWarnedAt: Instant? = null,
    val responseMetAt: Instant? = null,
    val responseBreachedAt: Instant? = null,

    val resolutionDueAt: Instant? = null,
    val resolutionWarningAt: Instant? = null,
    val resolutionWarnedAt: Instant? = null,
    val resolutionMetAt: Instant? = null,
    val resolutionBreachedAt: Instant? = null,

    /** Start of the current pause of the resolution clock, or null when it is not paused. */
    val pausedSince: Instant? = null,
    /** Total length of completed pauses. Every deadline of the resolution clock moves later by this much. */
    val pausedSeconds: Long = 0,

    val responseStatus: ClockStatus = ClockStatus.NO_POLICY,
    val resolutionStatus: ClockStatus = ClockStatus.NO_POLICY,
    val status: SlaStatus = SlaStatus.NO_POLICY,
    /** True once the incident is VERIFIED or CLOSED: the monitor stops evaluating it. */
    val complete: Boolean = false,
    /** Optimistic lock; managed by [SlaStore], never by the evaluator. */
    val version: Long = 0
) {
    init {
        require((targets == null) == (noPolicyReason != null)) { "noPolicyReason must be set exactly when there are no targets" }
        require(pausedSeconds >= 0) { "pausedSeconds must not be negative" }
    }
}

/** Something that happened to an SLA, reported once. Each becomes one audit event. */
data class SlaEvent(
    val type: Type,
    /** Null for events about the whole SLA (started, recalculated, policy missing). */
    val clock: SlaClock?,
    /** When it happened. A breach is dated at its deadline even if it is noticed later. */
    val at: Instant,
    /** Extra detail: the pause reason (`PENDING_INFO`, `REOPEN`) or the missing-policy reason. */
    val detail: String? = null
) {
    enum class Type(val auditAction: String) {
        STARTED("SLA_STARTED"),
        RECALCULATED("SLA_RECALCULATED"),
        POLICY_MISSING("SLA_POLICY_MISSING"),
        PAUSED("SLA_PAUSED"),
        RESUMED("SLA_RESUMED"),
        WARNING("SLA_WARNING_RAISED"),
        BREACHED("SLA_BREACHED"),
        MET("SLA_MET")
    }
}

data class SlaEvaluation(val record: SlaRecord, val events: List<SlaEvent>)

/**
 * Published (as a Spring application event, inside the monitor's transaction) for the SLA changes people must hear about:
 * a warning, a breach, or no applicable policy. P4's notifications react to it; it is not an audit record.
 */
data class SlaNotice(val incidentId: String, val kind: Kind, val clock: SlaClock?, val dueAt: Instant?) {
    enum class Kind { WARNING, BREACHED, POLICY_MISSING }
}
