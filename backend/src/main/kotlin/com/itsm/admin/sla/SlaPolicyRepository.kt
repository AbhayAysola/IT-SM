package com.itsm.admin.sla

import com.itsm.common.model.Priority
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface SlaPolicyRepository : JpaRepository<SlaPolicyEntity, Long> {

    /**
     * The only rows that can be the effective policy: ACTIVE, for [priority], and either specific to [serviceId] or
     * global. At most two rows exist (the partial unique indexes guarantee one per scope), so this is one cheap query.
     */
    @Query(
        """select p from SlaPolicyEntity p
           where p.active = true and p.priority = :priority and (p.serviceId = :serviceId or p.serviceId is null)"""
    )
    fun findEffectiveCandidates(@Param("serviceId") serviceId: Long, @Param("priority") priority: Priority): List<SlaPolicyEntity>

    /** The active policy occupying a scope, if any. [excludeId] skips the policy being edited or activated. */
    @Query(
        """select p from SlaPolicyEntity p
           where p.active = true and p.priority = :priority and p.id <> :excludeId
             and ((:serviceId is null and p.serviceId is null) or p.serviceId = :serviceId)"""
    )
    fun findActiveInScope(
        @Param("serviceId") serviceId: Long?,
        @Param("priority") priority: Priority,
        @Param("excludeId") excludeId: Long
    ): List<SlaPolicyEntity>

    /** Admin list. [pattern] is a lower-case LIKE pattern matched against the service name. */
    @Query(
        value = """select p from SlaPolicyEntity p
           where (:active is null or p.active = :active)
             and (:priority is null or p.priority = :priority)
             and (:serviceId is null or p.serviceId = :serviceId)
             and (:global is null or (:global = true and p.serviceId is null) or (:global = false and p.serviceId is not null))
             and (:pattern is null or p.serviceId in
                  (select s.id from ItServiceEntity s where lower(s.name) like :pattern escape '\'))""",
        countQuery = """select count(p) from SlaPolicyEntity p
           where (:active is null or p.active = :active)
             and (:priority is null or p.priority = :priority)
             and (:serviceId is null or p.serviceId = :serviceId)
             and (:global is null or (:global = true and p.serviceId is null) or (:global = false and p.serviceId is not null))
             and (:pattern is null or p.serviceId in
                  (select s.id from ItServiceEntity s where lower(s.name) like :pattern escape '\'))"""
    )
    fun search(
        @Param("active") active: Boolean?,
        @Param("priority") priority: Priority?,
        @Param("serviceId") serviceId: Long?,
        @Param("global") global: Boolean?,
        @Param("pattern") pattern: String?,
        pageable: Pageable
    ): Page<SlaPolicyEntity>
}
