package com.itsm.lifecycle

import com.itsm.common.model.Priority
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * P3's mapping of `incidents` (V20): the columns the lifecycle changes, plus title, description and created_at read-only
 * for display. P2's other intake columns are not mapped, and P2 owns the creating side. Hibernate's schema validation checks just the mapped columns.
 */
@Entity
@Table(name = "incidents")
class IncidentRow(
    @Id val id: String = "",
    @Column(nullable = false, updatable = false, insertable = false) val title: String = "",
    @Column(nullable = false, updatable = false, insertable = false) val description: String = "",
    @Column(name = "created_at", nullable = false, updatable = false, insertable = false) val createdAt: Instant = Instant.EPOCH,
    @Column(nullable = false) var state: String = "",
    @Column(name = "reporter_id", nullable = false) val reporterId: Long = 0,
    @Column(name = "assignee_id") var assigneeId: Long? = null,
    @Column(name = "category_id") var categoryId: Long? = null,
    @Column(name = "service_id") var serviceId: Long? = null,
    @Column var priority: String? = null,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.EPOCH,
    @Column(name = "assigned_at") var assignedAt: Instant? = null,
    @Column(name = "first_response_at") var firstResponseAt: Instant? = null,
    @Column(name = "resolved_at") var resolvedAt: Instant? = null,
    @Column(name = "closed_at") var closedAt: Instant? = null,
    @Version var version: Long = 0
)

@Repository
class JpaIncidentStore(
    @PersistenceContext private val em: EntityManager,
    private val jdbc: JdbcTemplate
) : IncidentStore {

    @Transactional(readOnly = true)
    override fun find(incidentId: String): IncidentRecord? = em.find(IncidentRow::class.java, incidentId)?.toRecord()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lock(incidentId: String): IncidentRecord? =
        em.find(IncidentRow::class.java, incidentId, LockModeType.PESSIMISTIC_WRITE)?.toRecord()

    @Transactional(propagation = Propagation.MANDATORY)
    override fun save(record: IncidentRecord) {
        val row = em.find(IncidentRow::class.java, record.id) ?: error("Incident ${record.id} vanished while it was locked")
        row.state = record.state.name
        row.assigneeId = record.assigneeId
        row.categoryId = record.categoryId
        row.serviceId = record.serviceId
        row.priority = record.priority?.name
        row.assignedAt = record.assignedAt
        row.firstResponseAt = record.firstResponseAt
        row.resolvedAt = record.resolvedAt
        row.closedAt = record.closedAt
        row.updatedAt = record.updatedAt
        em.flush() // a CHECK violation (V20) surfaces here, inside the caller's transaction
    }

    @Transactional(readOnly = true)
    override fun activeCounts(technicianIds: Collection<Long>): Map<Long, Int> {
        val ids = technicianIds.toSet()
        if (ids.isEmpty()) return emptyMap()
        val counted = ids.associateWithTo(HashMap()) { 0 }
        val states = WORKLOAD_STATES.joinToString(",") { "'$it'" } // constants from the enum, never user input
        jdbc.query(
            "SELECT assignee_id, count(*) FROM incidents WHERE assignee_id IN (${placeholders(ids.size)}) AND state IN ($states) GROUP BY assignee_id",
            RowCallbackHandler { rs -> counted[rs.getLong(1)] = rs.getInt(2) },
            *ids.toTypedArray()
        )
        return counted
    }

    @Transactional(readOnly = true)
    override fun lastAssignedAt(technicianIds: Collection<Long>): Map<Long, Instant> {
        val ids = technicianIds.toSet()
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<Long, Instant>()
        jdbc.query(
            "SELECT assignee_id, max(assigned_at) FROM incidents WHERE assignee_id IN (${placeholders(ids.size)}) AND assigned_at IS NOT NULL GROUP BY assignee_id",
            RowCallbackHandler { rs -> result[rs.getLong(1)] = rs.getTimestamp(2).toInstant() },
            *ids.toTypedArray()
        )
        return result
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun lockTechnician(technicianId: Long) {
        // Two int keys; a technician id beyond int range only collides with another id, which costs a wait, not correctness.
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", RowCallbackHandler { }, TECHNICIAN_LOCK_NAMESPACE, technicianId.toInt())
    }

    @Transactional(readOnly = true)
    override fun similarResolved(categoryId: Long, serviceId: Long?, excludeIncidentId: String, limit: Int): List<ResolvedIncident> =
        jdbc.query(
            """
            SELECT i.id, i.title, r.body, i.resolved_at, i.assignee_id, (i.service_id IS NOT DISTINCT FROM ?) AS same_service
            FROM incidents i
            JOIN LATERAL (
                SELECT e.body FROM investigation_entries e
                WHERE e.incident_id = i.id AND e.entry_type = 'RESOLUTION'
                ORDER BY e.created_at DESC, e.id DESC LIMIT 1
            ) r ON true
            WHERE i.category_id = ? AND i.resolved_at IS NOT NULL AND i.id <> ?
            ORDER BY same_service DESC, i.resolved_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                ResolvedIncident(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, java.time.OffsetDateTime::class.java).toInstant(),
                    rs.getLong(5), rs.getBoolean(6)
                )
            },
            serviceId, categoryId, excludeIncidentId, limit
        )

    private fun placeholders(n: Int) = List(n) { "?" }.joinToString(",")

    private fun IncidentRow.toRecord() = IncidentRecord(
        id = id, state = IncidentState.fromName(state), reporterId = reporterId, assigneeId = assigneeId,
        categoryId = categoryId, serviceId = serviceId, priority = priority?.let(Priority::fromCode),
        assignedAt = assignedAt, firstResponseAt = firstResponseAt, resolvedAt = resolvedAt, closedAt = closedAt,
        title = title, description = description, createdAt = createdAt, updatedAt = updatedAt
    )

    private companion object {
        val WORKLOAD_STATES: List<String> = IncidentState.entries.filter { it.countsTowardWorkload }.map { it.name }

        /** First key of the advisory lock pair; the second is the technician id. Distinct from P1's 7310002/7310004. */
        const val TECHNICIAN_LOCK_NAMESPACE = 7310030
    }
}
