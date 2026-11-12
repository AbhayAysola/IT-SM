package com.itsm.admin.itservice

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.BatchSize
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** Persistence model of an IT service. Never leaves the `admin.itservice` package. */
@Entity
@Table(name = "it_services")
class ItServiceEntity(
    @Column(nullable = false, length = 100)
    var name: String = "",

    @Column(length = 300)
    var description: String? = null,

    /** 1 (low) .. 5 (mission critical): the canonical representation of the ServiceView contract. */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "business_criticality", nullable = false)
    var businessCriticality: Int = 3,

    @Column(name = "owner_team", length = 100)
    var ownerTeam: String? = null,

    @Column(nullable = false)
    var active: Boolean = true,

    /** Ids of the services this one directly depends on (rows of `service_dependencies`). Batch-loaded. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "service_dependencies", joinColumns = [JoinColumn(name = "service_id")])
    @Column(name = "depends_on_id", nullable = false)
    @BatchSize(size = 100)
    var dependsOn: MutableSet<Long> = mutableSetOf()
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        private set

    @Version
    @Column(nullable = false)
    var version: Long = 0
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

    fun requireId(): Long = checkNotNull(id) { "IT service is not persisted yet" }
}
