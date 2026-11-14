package com.itsm.escalation

import com.itsm.common.model.Role
import com.itsm.common.spi.UserSummary
import com.itsm.escalation.EscalationTrigger.RESOLUTION_BREACHED
import com.itsm.escalation.EscalationTrigger.RESOLUTION_CRITICAL
import com.itsm.escalation.EscalationTrigger.RESOLUTION_OVERDUE
import com.itsm.escalation.EscalationTrigger.RESPONSE_BREACHED
import com.itsm.escalation.EscalationTrigger.UNASSIGNED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class EscalationPolicyTest {
    private val t0 = Instant.parse("2026-11-14T09:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))
    private val settings = EscalationSettings(unassignedMinutes = 30, secondLevelPct = 150, adminLevelPct = 200)

    /** An assigned incident with a 100-minute resolution target, tracked by the SLA monitor. */
    private fun working(state: String = "IN_PROGRESS", paused: Long = 0, pausedSince: Long? = null, metAt: Long? = null,
                        responseBreached: Long? = null, resolutionBreached: Long? = null) = EscalationCandidate(
        incidentId = "INC1001", state = state, assigneeId = 7, createdAt = t0,
        responseBreachedAt = responseBreached?.let(::at), resolutionBreachedAt = resolutionBreached?.let(::at),
        clockStart = t0, resolutionMinutes = 100, pausedSeconds = paused, pausedSince = pausedSince?.let(::at), resolutionMetAt = metAt?.let(::at)
    )

    private fun due(c: EscalationCandidate, now: Long, fired: Set<EscalationTrigger> = emptySet()) = EscalationPolicy.due(c, settings, fired, at(now))

    @Test fun `nothing is due for an incident that is on track`() {
        assertEquals(emptyList<EscalationTrigger>(), due(working(), 50))
    }

    @Test fun `recorded breaches escalate to the team lead`() {
        assertEquals(listOf(RESPONSE_BREACHED), due(working(responseBreached = 15), 20))
        assertEquals(listOf(RESOLUTION_BREACHED), due(working(resolutionBreached = 100), 101))
    }

    @Test fun `the resolution clock escalates further at 150 and 200 percent of its target`() {
        assertEquals(listOf(RESOLUTION_BREACHED), due(working(resolutionBreached = 100), 149))
        assertEquals(listOf(RESOLUTION_BREACHED, RESOLUTION_OVERDUE), due(working(resolutionBreached = 100), 150))
        assertEquals(listOf(RESOLUTION_BREACHED, RESOLUTION_OVERDUE, RESOLUTION_CRITICAL), due(working(resolutionBreached = 100), 200))
    }

    @Test fun `the thresholds come from the settings`() {
        val strict = EscalationSettings(unassignedMinutes = 5, secondLevelPct = 110, adminLevelPct = 120)
        assertEquals(listOf(RESOLUTION_OVERDUE), EscalationPolicy.due(working(), strict, emptySet(), at(110)))
        assertEquals(listOf(RESOLUTION_OVERDUE, RESOLUTION_CRITICAL), EscalationPolicy.due(working(), strict, emptySet(), at(120)))
    }

    @Test fun `paused time does not count, and a paused clock is judged at the start of its pause`() {
        // 60 minutes were paused: 150 % of 100 minutes is reached at minute 210, not 150.
        assertEquals(emptyList<EscalationTrigger>(), due(working(paused = 3600), 200))
        assertEquals(listOf(RESOLUTION_OVERDUE), due(working(paused = 3600), 210))
        // Paused since minute 120: nothing after that counts, however long the reporter takes.
        assertEquals(emptyList<EscalationTrigger>(), due(working(state = "PENDING_INFO", pausedSince = 120), 900))
    }

    @Test fun `a stopped resolution clock no longer escalates`() {
        assertEquals(emptyList<EscalationTrigger>(), due(working(metAt = 90), 500))
    }

    @Test fun `an incident nobody picked up escalates to the admins after the configured time`() {
        val unassigned = EscalationCandidate("INC1002", "CLASSIFIED", assigneeId = null, createdAt = t0)
        assertEquals(emptyList<EscalationTrigger>(), due(unassigned, 29))
        assertEquals(listOf(UNASSIGNED), due(unassigned, 30))
        assertEquals(emptyList<EscalationTrigger>(), due(unassigned.copy(state = "ASSIGNED", assigneeId = 7), 300), "assigned in the meantime")
    }

    @Test fun `a failed automatic assignment escalates at once, but only while nobody has it, and only once`() {
        val unassigned = EscalationCandidate("INC1002", "CLASSIFIED", assigneeId = null, createdAt = t0)
        val failed = { c: EscalationCandidate, fired: Set<EscalationTrigger> -> EscalationPolicy.due(c, settings, fired, at(1), assignmentFailed = true) }
        assertEquals(listOf(UNASSIGNED), failed(unassigned, emptySet()))
        assertEquals(emptyList<EscalationTrigger>(), failed(unassigned, setOf(UNASSIGNED)), "already escalated")
        assertEquals(emptyList<EscalationTrigger>(), failed(unassigned.copy(state = "ASSIGNED", assigneeId = 7), emptySet()), "assigned since")
    }

    @Test fun `without SLA facts only the unassigned rule applies`() {
        val noSla = EscalationCandidate("INC1003", "CLASSIFIED", assigneeId = null, createdAt = t0)
        assertEquals(listOf(UNASSIGNED), due(noSla, 10_000))
    }

    @Test fun `each trigger fires once, and later ones still fire`() {
        val c = working(responseBreached = 15, resolutionBreached = 100)
        assertEquals(listOf(RESOLUTION_OVERDUE), due(c, 160, setOf(RESPONSE_BREACHED, RESOLUTION_BREACHED)))
        assertEquals(emptyList<EscalationTrigger>(), due(c, 160, setOf(RESPONSE_BREACHED, RESOLUTION_BREACHED, RESOLUTION_OVERDUE)))
    }

    @Test fun `only open incidents escalate`() {
        for (state in listOf("RESOLVED", "VERIFIED", "CLOSED", "NEW")) {
            assertEquals(emptyList<EscalationTrigger>(), due(working(state = state, responseBreached = 15, resolutionBreached = 100), 300), state)
        }
        for (state in EscalationCandidate.OPEN_STATES) assertTrue(working(state = state).open, state)
    }

    @Test fun `results are ordered from the lowest tier up`() {
        val c = working(responseBreached = 15, resolutionBreached = 100)
        val tiers = due(c, 250).map { it.tier }
        assertEquals(tiers.sortedBy { it.ordinal }, tiers)
    }

    // --- targets ------------------------------------------------------------------------------------------------------

    private fun user(id: Long, role: Role = Role.TECHNICIAN, active: Boolean = true) = UserSummary(id, "u$id@campus.example", "User $id", role, active)
    private val admins = listOf(user(90, Role.ADMIN), user(91, Role.ADMIN), user(92, Role.ADMIN, active = false))

    @Test fun `the team lead tier goes to the nearest manager, the next tier to the one above`() {
        val chain = listOf(user(10), user(11))
        assertEquals(listOf(10L), EscalationTargets.choose(EscalationTier.TEAM_LEAD, chain) { admins }.map { it.id })
        assertEquals(listOf(11L), EscalationTargets.choose(EscalationTier.NEXT_LEVEL, chain) { admins }.map { it.id })
    }

    @Test fun `a short or empty reporting line falls back to the active admins`() {
        assertEquals(listOf(90L, 91L), EscalationTargets.choose(EscalationTier.NEXT_LEVEL, listOf(user(10))) { admins }.map { it.id })
        assertEquals(listOf(90L, 91L), EscalationTargets.choose(EscalationTier.TEAM_LEAD, emptyList()) { admins }.map { it.id })
        assertEquals(listOf(90L, 91L), EscalationTargets.choose(EscalationTier.ADMINS, listOf(user(10), user(11))) { admins }.map { it.id })
    }

    @Test fun `admins are only looked up when needed`() {
        var asked = false
        EscalationTargets.choose(EscalationTier.TEAM_LEAD, listOf(user(10))) { asked = true; admins }
        assertFalse(asked)
    }

    // --- settings -----------------------------------------------------------------------------------------------------

    @Test fun `settings are validated like the database checks them`() {
        assertEquals(emptyMap<String, String>(), EscalationSettings.validate(30, 150, 200))
        assertEquals(setOf("unassignedMinutes"), EscalationSettings.validate(0, 150, 200).keys)
        assertEquals(setOf("unassignedMinutes"), EscalationSettings.validate(10_081, 150, 200).keys)
        assertEquals(setOf("secondLevelPct"), EscalationSettings.validate(30, 100, 200).keys)
        assertEquals(setOf("adminLevelPct"), EscalationSettings.validate(30, 150, 150).keys)
        assertEquals(setOf("adminLevelPct"), EscalationSettings.validate(30, 150, 1001).keys)
        assertEquals(setOf("secondLevelPct", "adminLevelPct"), EscalationSettings.validate(30, 2000, 50).keys)
    }
}
