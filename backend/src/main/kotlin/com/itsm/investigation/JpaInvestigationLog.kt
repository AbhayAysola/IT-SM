package com.itsm.investigation

import com.itsm.lifecycle.IncidentState
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PersistenceContext
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** Row of `investigation_entries` (V30). Internal to this module: callers see [InvestigationEntry]. */
@Entity
@Immutable
@Table(name = "investigation_entries")
class InvestigationEntryEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "incident_id", nullable = false) val incidentId: String = "",
    @Column(name = "author_id", nullable = false) val authorId: Long = 0,
    @Column(name = "entry_type", nullable = false) val entryType: String = "",
    @Column(nullable = false) val visibility: String = "",
    @Column(nullable = false) val body: String = "",
    @Column(name = "incident_state", nullable = false) val incidentState: String = "",
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.EPOCH
)

interface InvestigationEntryRepository : JpaRepository<InvestigationEntryEntity, Long> {
    fun findByIncidentIdOrderByCreatedAtAscIdAsc(incidentId: String): List<InvestigationEntryEntity>
}

/**
 * Stores the timeline. [append] is `MANDATORY`: it fails fast outside a transaction, so an entry can never be
 * written apart from the state change it describes (the rule in [InvestigationLog]). `persist` rather than
 * Spring Data's `save`, so the insert is explicit; `@Repository` translates database errors for the global handler.
 */
@Repository
class JpaInvestigationLog(
    @PersistenceContext private val em: EntityManager,
    private val repo: InvestigationEntryRepository
) : InvestigationLog {

    @Transactional(propagation = Propagation.MANDATORY)
    override fun append(entry: InvestigationEntry) {
        em.persist(
            InvestigationEntryEntity(
                incidentId = entry.incidentId, authorId = entry.authorId, entryType = entry.type.name,
                visibility = entry.visibility.name, body = entry.body, incidentState = entry.incidentState.name,
                createdAt = entry.createdAt
            )
        )
        em.flush() // surface a constraint violation here, in the caller's transaction, not at commit
    }

    @Transactional(readOnly = true)
    override fun list(incidentId: String): List<InvestigationEntry> =
        repo.findByIncidentIdOrderByCreatedAtAscIdAsc(incidentId).map {
            InvestigationEntry(
                incidentId = it.incidentId, authorId = it.authorId, type = InvestigationEntryType.valueOf(it.entryType),
                visibility = Visibility.valueOf(it.visibility), body = it.body,
                incidentState = IncidentState.fromName(it.incidentState), createdAt = it.createdAt
            )
        }
}
