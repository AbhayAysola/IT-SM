package com.itsm.admin.category

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
import java.time.Instant

/** Persistence model of an incident category. Never leaves the `admin.category` package. */
@Entity
@Table(name = "incident_categories")
class CategoryEntity(
    @Column(nullable = false, updatable = false, length = 40)
    val code: String = "",

    @Column(nullable = false, length = 100)
    var name: String = "",

    @Column(length = 300)
    var description: String? = null,

    @Column(nullable = false)
    var active: Boolean = true,

    /**
     * Normalised keywords. Rows also carry `category_active`, which the database maintains (insert trigger +
     * cascading composite FK) and which this mapping deliberately does not see. Loaded in batches to avoid N+1.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "incident_category_keywords", joinColumns = [JoinColumn(name = "category_id")])
    @Column(name = "keyword", nullable = false, length = 30)
    @BatchSize(size = 100)
    var keywords: MutableSet<String> = mutableSetOf()
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

    fun requireId(): Long = checkNotNull(id) { "Category is not persisted yet" }
}
