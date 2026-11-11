package com.itsm.investigation

/**
 * Storage contract for the timeline. The implementation (a repository over a P3 table, `V30-V39`) is written
 * once incident storage exists; the rules around it are in [InvestigationPolicy] and are testable now.
 *
 * Required behaviour of any implementation:
 *  * **Append-only.** There is intentionally no update or delete. Enforce it in the database too (as the audit
 *    table does, with a trigger), not only in code.
 *  * **Same transaction as the state change.** Call [append] inside the `@Transactional` method that applies the
 *    lifecycle transition and publishes the audit event, so the timeline and the state commit or roll back together.
 *  * **Stable order.** [list] returns oldest first, ties broken by insertion order.
 *  * **No foreign key to incidents** is assumed here; the incident id is a string (`INC1024`) owned by P2.
 *  * The text of an entry is never copied into audit metadata or logs (it can hold personal data).
 */
interface InvestigationLog {
    fun append(entry: InvestigationEntry)

    /** Every entry for the incident, oldest first, internal ones included. Filter with [InvestigationPolicy.visibleTo]. */
    fun list(incidentId: String): List<InvestigationEntry>
}
