package com.itsm.admin.itservice

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ItServiceRepository : JpaRepository<ItServiceEntity, Long> {

    @EntityGraph(attributePaths = ["dependsOn"])
    fun findAllByActiveTrueOrderByNameAsc(): List<ItServiceEntity>

    @Query(
        """select count(s) > 0 from ItServiceEntity s
           where lower(s.name) = lower(:name) and (:excludeId is null or s.id <> :excludeId)"""
    )
    fun existsByNameIgnoreCase(@Param("name") name: String, @Param("excludeId") excludeId: Long?): Boolean

    /** Every edge of the dependency graph as (serviceId, dependsOnId). The graph is small: one query. */
    @Query("select s.id, d from ItServiceEntity s join s.dependsOn d")
    fun allEdges(): List<Array<Any>>

    /** Direct dependents of [serviceId], active or not (reverse edges). */
    @Query("select s.id from ItServiceEntity s join s.dependsOn d where d = :serviceId")
    fun dependentIdsOf(@Param("serviceId") serviceId: Long): List<Long>

    /** Names of ACTIVE services that directly depend on [serviceId]. */
    @Query("select s.name from ItServiceEntity s join s.dependsOn d where d = :serviceId and s.active = true order by s.name")
    fun activeDependentNames(@Param("serviceId") serviceId: Long): List<String>

    /** (dependentId, dependsOnId) for every edge pointing at one of [ids]: dependents of a whole page in one query. */
    @Query("select s.id, d from ItServiceEntity s join s.dependsOn d where d in :ids")
    fun dependentEdgesOf(@Param("ids") ids: Collection<Long>): List<Array<Any>>

    /** (id, name, active) for [ids]: labels for dependency references without loading entities. */
    @Query("select s.id, s.name, s.active from ItServiceEntity s where s.id in :ids")
    fun summariesOf(@Param("ids") ids: Collection<Long>): List<Array<Any>>

    /**
     * Admin list. [ownerTeam] must already be lower-case and [pattern] is a lower-case LIKE pattern from
     * CatalogText.containsPattern. No SQL function is applied to a parameter: a null parameter is bound untyped and
     * PostgreSQL cannot resolve e.g. `lower(NULL::bytea)`.
     */
    @Query(
        value = """select s from ItServiceEntity s
           where (:active is null or s.active = :active)
             and (:criticality is null or s.businessCriticality = :criticality)
             and (:ownerTeam is null or lower(s.ownerTeam) = :ownerTeam)
             and (:pattern is null
                  or lower(s.name) like :pattern escape '\'
                  or lower(coalesce(s.description, '')) like :pattern escape '\'
                  or lower(coalesce(s.ownerTeam, '')) like :pattern escape '\')""",
        countQuery = """select count(s) from ItServiceEntity s
           where (:active is null or s.active = :active)
             and (:criticality is null or s.businessCriticality = :criticality)
             and (:ownerTeam is null or lower(s.ownerTeam) = :ownerTeam)
             and (:pattern is null
                  or lower(s.name) like :pattern escape '\'
                  or lower(coalesce(s.description, '')) like :pattern escape '\'
                  or lower(coalesce(s.ownerTeam, '')) like :pattern escape '\')"""
    )
    fun search(
        @Param("active") active: Boolean?,
        @Param("criticality") criticality: Int?,
        @Param("ownerTeam") ownerTeam: String?,
        @Param("pattern") pattern: String?,
        pageable: Pageable
    ): Page<ItServiceEntity>
}
