package com.itsm.audit

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Already-validated filters. Every field is an exact match except the time range: `occurredAt >= from` and `< to`. */
data class AuditEventFilter(
    val action: String? = null,
    val actorType: String? = null,
    val actorId: Long? = null,
    val actorRole: String? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val incidentId: String? = null,
    val from: Instant? = null,
    val to: Instant? = null
)

/**
 * Read side of the audit table. Kept apart from [AuditEventRepository] on purpose: that interface can only insert,
 * and this class can only SELECT (it has no write method and takes no entity). Queries are built with the Criteria
 * API so only the filters that were actually supplied appear in the SQL; a page is one LIMIT/OFFSET select plus one
 * count, never the whole table.
 */
@Component
class AuditEventReader(@PersistenceContext private val em: EntityManager) {

    fun search(filter: AuditEventFilter, pageable: Pageable): Page<AuditEventEntity> {
        val cb = em.criteriaBuilder

        val countQuery = cb.createQuery(Long::class.java)
        val countRoot = countQuery.from(AuditEventEntity::class.java)
        countQuery.select(cb.count(countRoot)).where(*predicates(cb, countRoot, filter))
        val total = em.createQuery(countQuery).singleResult
        if (total == 0L || pageable.offset >= total) return PageImpl(emptyList(), pageable, total)

        val query = cb.createQuery(AuditEventEntity::class.java)
        val root = query.from(AuditEventEntity::class.java)
        query.select(root).where(*predicates(cb, root, filter))
        query.orderBy(pageable.sort.map { order ->
            if (order.isAscending) cb.asc(root.get<Any>(order.property)) else cb.desc(root.get<Any>(order.property))
        }.toList())
        val rows = em.createQuery(query)
            .setFirstResult(Math.toIntExact(pageable.offset))
            .setMaxResults(pageable.pageSize)
            .resultList
        return PageImpl(rows, pageable, total)
    }

    fun findByEventId(eventId: UUID): AuditEventEntity? {
        val cb = em.criteriaBuilder
        val query = cb.createQuery(AuditEventEntity::class.java)
        val root = query.from(AuditEventEntity::class.java)
        query.select(root).where(cb.equal(root.get<UUID>("eventId"), eventId))
        return em.createQuery(query).setMaxResults(1).resultList.firstOrNull()
    }

    private fun predicates(cb: CriteriaBuilder, root: Root<AuditEventEntity>, f: AuditEventFilter): Array<Predicate> =
        buildList {
            f.action?.let { add(cb.equal(root.get<String>("action"), it)) }
            f.actorType?.let { add(cb.equal(root.get<String>("actorType"), it)) }
            f.actorId?.let { add(cb.equal(root.get<Long>("actorId"), it)) }
            f.actorRole?.let { add(cb.equal(root.get<String>("actorRole"), it)) }
            f.entityType?.let { add(cb.equal(root.get<String>("entityType"), it)) }
            f.entityId?.let { add(cb.equal(root.get<String>("entityId"), it)) }
            f.incidentId?.let { add(cb.equal(root.get<String>("incidentId"), it)) }
            f.from?.let { add(cb.greaterThanOrEqualTo(root.get<Instant>("occurredAt"), it)) }
            f.to?.let { add(cb.lessThan(root.get<Instant>("occurredAt"), it)) }
        }.toTypedArray()
}
