package com.itsm.escalation

import com.itsm.common.spi.UserDirectory
import com.itsm.common.spi.UserSummary
import org.springframework.stereotype.Component

/**
 * Who an escalation goes to, from P1's [UserDirectory]: the assignee's reporting line (nearest manager first, inactive
 * managers already skipped by P1), then the admins. An incident without an assignee, or an assignee without a team lead,
 * goes straight to the admins.
 */
@Component
class EscalationTargets(private val directory: UserDirectory) {

    fun resolve(tier: EscalationTier, assigneeId: Long?): List<UserSummary> {
        val chain = if (tier == EscalationTier.ADMINS || assigneeId == null) emptyList() else directory.escalationChain(assigneeId)
        return choose(tier, chain) { directory.listAdmins() }
    }

    companion object {
        /** Pure choice, given the reporting line; [admins] is only asked when it is needed. */
        fun choose(tier: EscalationTier, chain: List<UserSummary>, admins: () -> List<UserSummary>): List<UserSummary> {
            val manager = when (tier) {
                EscalationTier.TEAM_LEAD -> chain.getOrNull(0)
                EscalationTier.NEXT_LEVEL -> chain.getOrNull(1)
                EscalationTier.ADMINS -> null
            }
            return if (manager != null) listOf(manager) else admins().filter { it.active }
        }
    }
}
