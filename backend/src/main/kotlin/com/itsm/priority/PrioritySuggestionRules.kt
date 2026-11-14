package com.itsm.priority

import com.itsm.common.model.Priority
import com.itsm.common.spi.PriorityAssessment

/** Why a priority looks out of date. Stored, so the values are a contract with V45's CHECK. */
enum class SuggestionRule { CRITICALITY, DUPLICATES }

/** What the rules know about one open incident. */
data class SuggestionInput(
    val current: Priority,
    /** P4's assessment of the incident's stored answers with its service as configured now; null without answers. */
    val assessed: PriorityAssessment?,
    /** An admin already changed this incident's priority by hand: their decision is not second-guessed by [SuggestionRule.CRITICALITY]. */
    val manuallyChanged: Boolean,
    /** Distinct new reports in the duplicate window flagged as possible duplicates of this incident. */
    val duplicateReports: Int
)

data class PrioritySuggestion(val priority: Priority, val rules: List<SuggestionRule>, val reasons: List<String>)

/**
 * When an open incident's priority looks out of date (docs/architecture/p4.md section 1, "Priority suggestions"). Pure.
 *
 * * **CRITICALITY:** P4's priority rules, applied now to the reporter's answers and the service as configured today, give a
 *   different priority (the service's business criticality or dependents changed since intake). Skipped once an admin has
 *   changed the priority by hand.
 * * **DUPLICATES:** at least [DUPLICATE_THRESHOLD] new reports were flagged as possible duplicates of it: a spreading
 *   problem. One level higher (P1 stays P1).
 *
 * The most urgent candidate wins; only rules pointing the same way are kept as its reasons. Nothing when it equals the
 * current priority.
 */
object PrioritySuggestionRules {
    const val DUPLICATE_THRESHOLD = 3

    fun suggest(input: SuggestionInput): PrioritySuggestion? {
        val candidates = buildList {
            val assessed = input.assessed
            if (assessed != null && !input.manuallyChanged && assessed.priority != input.current) {
                val why = assessed.reasons.joinToString(" ").ifBlank { "its answers and service now point to ${assessed.priority.name}" }
                add(Triple(assessed.priority, SuggestionRule.CRITICALITY, "The priority rules now give ${assessed.priority.name} for it: $why"))
            }
            if (input.duplicateReports >= DUPLICATE_THRESHOLD && input.current != Priority.P1) {
                val up = Priority.entries[input.current.ordinal - 1]
                add(Triple(up, SuggestionRule.DUPLICATES, "${input.duplicateReports} new reports look like duplicates of it: the problem is spreading."))
            }
        }
        val pick = candidates.minByOrNull { it.first.ordinal }?.first ?: return null
        if (pick == input.current) return null
        val raising = pick.ordinal < input.current.ordinal
        val same = candidates.filter { (p, _, _) -> if (raising) p.ordinal < input.current.ordinal else p.ordinal > input.current.ordinal }
        return PrioritySuggestion(pick, same.map { it.second }, same.map { it.third })
    }
}
