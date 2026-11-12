package com.itsm.admin.sla

import com.itsm.common.model.Priority
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

/** Persistence model of an SLA policy. Never leaves the `admin.sla` package. */
@Entity
@Table(name = "sla_policies")
class SlaPolicyEntity(
    /** null = the global policy for [priority]. Immutable after creation. */
    @Column(name = "service_id", updatable = false)
    val serviceId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 2)
    val priority: Priority = Priority.P3,

    @Column(name = "response_minutes", nullable = false)
    var responseMinutes: Int = 0,

    @Column(name = "resolution_minutes", nullable = false)
    var resolutionMinutes: Int = 0,

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "warning_threshold_pct", nullable = false)
    var warningThresholdPct: Int = SlaRules.DEFAULT_WARNING_PCT,

    @Column(nullable = false)
    var active: Boolean = true
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

    fun requireId(): Long = checkNotNull(id) { "SLA policy is not persisted yet" }
}
