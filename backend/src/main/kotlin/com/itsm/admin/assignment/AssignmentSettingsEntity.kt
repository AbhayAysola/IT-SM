package com.itsm.admin.assignment

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.MapKeyColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.BatchSize
import java.time.Instant

/** The single assignment configuration row (id is always 1). Never leaves the `admin.assignment` package. */
@Entity
@Table(name = "assignment_settings")
class AssignmentSettingsEntity(
    @Column(name = "active_strategy", nullable = false, length = 50)
    var activeStrategy: String = "",

    @Column(name = "default_max_workload", nullable = false)
    var defaultMaxWorkload: Int = 0,

    /** categoryId -> strategy key. Rows of `assignment_category_overrides`. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "assignment_category_overrides", joinColumns = [JoinColumn(name = "settings_id")])
    @MapKeyColumn(name = "category_id")
    @Column(name = "strategy", nullable = false, length = 50)
    @BatchSize(size = 10)
    var categoryOverrides: MutableMap<Long, String> = mutableMapOf()
) {
    @Id
    @Column(nullable = false)
    var id: Short = SINGLETON_ID
        private set

    @Version
    @Column(nullable = false)
    var version: Long = 0
        private set

    /** User id of the last editor (no personal data); null for the seeded row. */
    @Column(name = "updated_by")
    var updatedBy: Long? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.EPOCH

    companion object {
        const val SINGLETON_ID: Short = 1
    }
}
