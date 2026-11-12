package com.itsm.admin.technician

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapKeyColumn
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.BatchSize
import java.time.Instant

/**
 * Persistence model of a technician profile. Never leaves the `admin.technician` package. The id IS the user id (1:1 with
 * `users`); identity data is read from `users` and never copied here.
 */
@Entity
@Table(name = "technician_profiles")
class TechnicianProfileEntity(
    @Id @Column(name = "user_id")
    val userId: Long = 0,

    @Column(name = "max_workload", nullable = false)
    var maxWorkload: Int = 0,

    @Column(name = "experience_years", nullable = false)
    var experienceYears: Int = 0,

    @Column(nullable = false)
    var available: Boolean = true,

    @Column(name = "is_team_lead", nullable = false)
    var teamLead: Boolean = false,

    @Column(name = "reports_to_user_id")
    var reportsToUserId: Long? = null,

    @Column(nullable = false)
    var active: Boolean = true,

    /** categoryId -> proficiency 1..5. The table's FK and CHECKs back the application rules. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "technician_skills", joinColumns = [JoinColumn(name = "user_id")])
    @MapKeyColumn(name = "category_id")
    @Column(name = "proficiency", nullable = false)
    @BatchSize(size = 100)
    var skills: MutableMap<Long, Int> = mutableMapOf()
) {
    /** null until first persisted: this is also how Spring Data tells a new profile from an existing one (assigned id). */
    @Version
    @Column(nullable = false)
    var version: Long? = null
        private set

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.EPOCH
        private set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH
        private set

    @PrePersist
    fun onCreate() {
        createdAt = Instant.now()
        updatedAt = createdAt
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    /** Makes the row dirty so the optimistic-lock version moves even when only the skill set changed. */
    fun touch() {
        updatedAt = Instant.now()
    }
}
