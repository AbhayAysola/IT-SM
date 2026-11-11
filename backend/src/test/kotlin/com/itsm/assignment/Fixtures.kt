package com.itsm.assignment

import com.itsm.common.model.Priority
import com.itsm.common.spi.AssignmentSettingsView
import com.itsm.common.spi.TechnicianSummary
import java.time.Instant

internal const val CAT = 7L

internal fun tech(
    id: Long, max: Int = 5, years: Int = 3, available: Boolean = true, skills: Map<Long, Int> = emptyMap()
) = TechnicianSummary(
    userId = id, name = "Tech $id", email = "t$id@campus.example", available = available, maxWorkload = max,
    experienceYears = years, isTeamLead = false, reportsToUserId = null, skills = skills
)

internal fun cand(
    id: Long, active: Int = 0, max: Int = 5, years: Int = 3, available: Boolean = true,
    skills: Map<Long, Int> = emptyMap(), last: Instant? = null
) = Candidate(tech(id, max, years, available, skills), active, last)

internal fun req(priority: Priority = Priority.P3, category: Long? = CAT, exclude: Set<Long> = emptySet()) =
    AssignmentRequest(category, priority, exclude)

internal fun settings(
    strategy: String = StrategyKeys.LOAD_BASED, defaultMax: Int = 5, overrides: Map<Long, String> = emptyMap()
) = AssignmentSettingsView(strategy, defaultMax, overrides)

internal fun List<Candidate>.ids() = map { it.id }
