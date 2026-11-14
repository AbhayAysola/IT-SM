package com.itsm.sla

import java.time.Duration
import java.time.Instant

/**
 * The SLA rules of docs/architecture/p4.md section 2, as one pure function: given the record stored at the last run (or
 * none), a fresh snapshot of the incident, the targets if they were looked up for this run, and the current time, it
 * returns the new record and the events that happened since. No clock, database or beans, so every rule is unit-tested.
 *
 * Both clocks start at the incident's `created_at`, on a 24x7 calendar:
 * * **response** stops at `first_response_at` (never cleared, so a reassignment does not restart it);
 * * **resolution** stops at `resolved_at`, is **paused** while the incident is `PENDING_INFO` (waiting on the reporter),
 *   and **resumes** on a reopen, with the time spent in `RESOLVED` counted as paused.
 *
 * A clock **warns** once `warningThresholdPct` of its target has elapsed while it is still running, and is **breached**
 * once its deadline passes before it stops. A breach is recorded once, dated at the deadline, and never removed.
 *
 * Pause and reopen times are the incident's `updated_at` (every lifecycle transition sets it) when the change is evaluated.
 * P4 evaluates right after each P2/P3 domain event, so they are exact. Only a change whose event reaction failed is left
 * to the next sweep; then two changes between two runs (for example a reopen and a second resolve) can be missed, and the
 * error is at most one interval.
 */
object SlaEvaluator {

    fun evaluate(previous: SlaRecord?, snapshot: IncidentSnapshot, lookup: TargetLookup?, now: Instant): SlaEvaluation {
        require(previous == null || previous.incidentId == snapshot.incidentId) { "record and snapshot are for different incidents" }
        require(previous != null || lookup != null) { "a new SLA record needs a target lookup" }
        val events = mutableListOf<SlaEvent>()

        var r = applyTargets(previous, snapshot, lookup, now, events)
        r = recordFacts(r, snapshot, now, events)
        return SlaEvaluation(judge(previous, r, snapshot, now, events), events.toList())
    }

    // --- 1. targets -------------------------------------------------------------------------------------------------

    private fun applyTargets(previous: SlaRecord?, s: IncidentSnapshot, lookup: TargetLookup?, now: Instant, events: MutableList<SlaEvent>): SlaRecord =
        when (lookup) {
            null -> previous!!
            is TargetLookup.Found -> {
                val old = previous?.targets
                when {
                    old == null -> events += SlaEvent(SlaEvent.Type.STARTED, null, now)
                    old != lookup.targets || previous.priority != s.priority || previous.serviceId != s.serviceId ->
                        events += SlaEvent(SlaEvent.Type.RECALCULATED, null, now)
                }
                (previous ?: newRecord(s, lookup.targets, null))
                    .copy(priority = s.priority, serviceId = s.serviceId, targets = lookup.targets, noPolicyReason = null)
            }
            is TargetLookup.Missing -> when {
                // Tracking already runs on the old targets: keep them rather than stop tracking. (Priority cannot change
                // after creation today; the monitor retries the lookup on every run while the priority differs.)
                previous?.targets != null -> previous
                else -> {
                    if (previous?.noPolicyReason != lookup.reason) {
                        events += SlaEvent(SlaEvent.Type.POLICY_MISSING, null, now, lookup.reason.name)
                    }
                    (previous ?: newRecord(s, null, lookup.reason))
                        .copy(priority = s.priority, serviceId = s.serviceId, noPolicyReason = lookup.reason)
                }
            }
        }

    private fun newRecord(s: IncidentSnapshot, targets: SlaTargets?, reason: NoPolicyReason?) =
        SlaRecord(s.incidentId, s.priority, s.serviceId, clockStart = s.createdAt, targets = targets, noPolicyReason = reason)

    // --- 2. facts: when clocks stopped, paused and resumed (recorded with or without targets) -------------------------

    private fun recordFacts(record: SlaRecord, s: IncidentSnapshot, now: Instant, events: MutableList<SlaEvent>): SlaRecord {
        var r = record
        if (r.responseMetAt == null && s.firstResponseAt != null) r = r.copy(responseMetAt = s.firstResponseAt)

        val metAt = r.resolutionMetAt
        if (metAt != null && s.resolvedAt == null) {
            // Reopened: the resolution clock resumes; the reporter had been told it was fixed, so that time is paused.
            val reopenedAt = clamp(s.updatedAt, metAt, now)
            r = r.copy(resolutionMetAt = null, pausedSeconds = r.pausedSeconds + seconds(metAt, reopenedAt))
            events += SlaEvent(SlaEvent.Type.RESUMED, SlaClock.RESOLUTION, reopenedAt, REOPEN)
        } else if (metAt != null && s.resolvedAt != null && s.resolvedAt != metAt) {
            // Reopened and resolved again between two runs: the reopen time is unknown, so nothing counts as paused.
            r = r.copy(resolutionMetAt = s.resolvedAt)
        }

        val pausedSince = r.pausedSince
        if (pausedSince != null && !s.waitingOnReporter) {
            // The reporter answered (or, between two runs, the incident moved on and was even resolved).
            val resumedAt = clamp(s.resolvedAt ?: s.updatedAt, pausedSince, now)
            r = r.copy(pausedSince = null, pausedSeconds = r.pausedSeconds + seconds(pausedSince, resumedAt))
            events += SlaEvent(SlaEvent.Type.RESUMED, SlaClock.RESOLUTION, resumedAt, IncidentSnapshot.PENDING_INFO)
        }
        if (r.pausedSince == null && s.waitingOnReporter && r.resolutionMetAt == null) {
            val since = clamp(s.updatedAt, s.createdAt, now)
            r = r.copy(pausedSince = since)
            events += SlaEvent(SlaEvent.Type.PAUSED, SlaClock.RESOLUTION, since, IncidentSnapshot.PENDING_INFO)
        }

        if (r.resolutionMetAt == null && s.resolvedAt != null) r = r.copy(resolutionMetAt = s.resolvedAt)
        return r
    }

    // --- 3. judgement: deadlines, warnings, breaches and statuses (needs targets) -------------------------------------

    private fun judge(previous: SlaRecord?, r: SlaRecord, s: IncidentSnapshot, now: Instant, events: MutableList<SlaEvent>): SlaRecord {
        val t = r.targets ?: return r.copy(
            responseDueAt = null, responseWarningAt = null, resolutionDueAt = null, resolutionWarningAt = null,
            responseStatus = ClockStatus.NO_POLICY, resolutionStatus = ClockStatus.NO_POLICY, status = SlaStatus.NO_POLICY,
            complete = s.trackingFinished
        )

        val responseDue = r.clockStart.plus(minutes(t.responseMinutes))
        val responseWarning = r.clockStart.plus(fraction(t.responseMinutes, t.warningThresholdPct))
        val (responseBreached, responseWarned) = judgeClock(
            SlaClock.RESPONSE, r.responseMetAt, responseDue, responseWarning, now, r.responseBreachedAt, r.responseWarnedAt, events
        )

        val paused = Duration.ofSeconds(r.pausedSeconds)
        val resolutionDue = r.clockStart.plus(minutes(t.resolutionMinutes)).plus(paused)
        val resolutionWarning = r.clockStart.plus(fraction(t.resolutionMinutes, t.warningThresholdPct)).plus(paused)
        // While paused the clock is stopped at the start of the pause: nothing after it can warn or breach.
        val (resolutionBreached, resolutionWarned) = judgeClock(
            SlaClock.RESOLUTION, r.resolutionMetAt, resolutionDue, resolutionWarning, r.pausedSince ?: now,
            r.resolutionBreachedAt, r.resolutionWarnedAt, events
        )

        val responseStatus = when {
            responseBreached != null -> ClockStatus.BREACHED
            r.responseMetAt != null -> ClockStatus.MET
            !responseWarning.isAfter(now) -> ClockStatus.WARNING
            else -> ClockStatus.RUNNING
        }
        val resolutionStatus = when {
            resolutionBreached != null -> ClockStatus.BREACHED
            r.resolutionMetAt != null -> ClockStatus.MET
            r.pausedSince != null -> ClockStatus.PAUSED
            !resolutionWarning.isAfter(now) -> ClockStatus.WARNING
            else -> ClockStatus.RUNNING
        }
        if (responseStatus == ClockStatus.MET && previous?.responseStatus != ClockStatus.MET) {
            events += SlaEvent(SlaEvent.Type.MET, SlaClock.RESPONSE, r.responseMetAt!!)
        }
        if (resolutionStatus == ClockStatus.MET && previous?.resolutionStatus != ClockStatus.MET) {
            events += SlaEvent(SlaEvent.Type.MET, SlaClock.RESOLUTION, r.resolutionMetAt!!)
        }

        return r.copy(
            responseDueAt = responseDue, responseWarningAt = responseWarning,
            responseWarnedAt = responseWarned, responseBreachedAt = responseBreached,
            resolutionDueAt = resolutionDue, resolutionWarningAt = resolutionWarning,
            resolutionWarnedAt = resolutionWarned, resolutionBreachedAt = resolutionBreached,
            responseStatus = responseStatus, resolutionStatus = resolutionStatus,
            status = overall(responseStatus, resolutionStatus),
            complete = s.trackingFinished
        )
    }

    /** Returns the (possibly newly set) breach and warning instants, adding an event for each that is new. */
    private fun judgeClock(
        clock: SlaClock, metAt: Instant?, due: Instant, warningAt: Instant, effectiveNow: Instant,
        breachedAt: Instant?, warnedAt: Instant?, events: MutableList<SlaEvent>
    ): Pair<Instant?, Instant?> {
        var breached = breachedAt
        var warned = warnedAt
        val missedDeadline = if (metAt != null) metAt.isAfter(due) else !due.isAfter(effectiveNow)
        if (breached == null && missedDeadline) {
            breached = due
            events += SlaEvent(SlaEvent.Type.BREACHED, clock, due)
        }
        val reachedWarning = (metAt == null || metAt.isAfter(warningAt)) && !warningAt.isAfter(effectiveNow)
        if (warned == null && reachedWarning) {
            warned = warningAt
            // Only a clock that is still open and not breached is worth a warning; otherwise it is recorded silently.
            if (breached == null && metAt == null) events += SlaEvent(SlaEvent.Type.WARNING, clock, warningAt)
        }
        return breached to warned
    }

    private fun overall(response: ClockStatus, resolution: ClockStatus): SlaStatus = when {
        response == ClockStatus.BREACHED || resolution == ClockStatus.BREACHED -> SlaStatus.BREACHED
        response == ClockStatus.WARNING || resolution == ClockStatus.WARNING -> SlaStatus.WARNING
        resolution == ClockStatus.PAUSED -> SlaStatus.PAUSED
        response == ClockStatus.RUNNING || resolution == ClockStatus.RUNNING -> SlaStatus.RUNNING
        else -> SlaStatus.MET
    }

    const val REOPEN = "REOPEN"

    private fun minutes(m: Int) = Duration.ofMinutes(m.toLong())
    private fun fraction(minutes: Int, pct: Int) = Duration.ofSeconds(minutes * 60L * pct / 100)
    private fun seconds(from: Instant, to: Instant) = Duration.between(from, to).seconds.coerceAtLeast(0)
    private fun clamp(value: Instant, low: Instant, high: Instant): Instant =
        if (value.isBefore(low)) low else if (value.isAfter(high)) maxOf(low, high) else value
}
