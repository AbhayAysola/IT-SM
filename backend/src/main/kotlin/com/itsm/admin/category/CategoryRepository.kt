package com.itsm.admin.category

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CategoryRepository : JpaRepository<CategoryEntity, Long> {

    fun findByCode(code: String): CategoryEntity?

    @EntityGraph(attributePaths = ["keywords"])
    fun findAllByActiveTrueOrderByNameAsc(): List<CategoryEntity>

    fun countByActiveTrue(): Long

    @Query(
        """select count(c) > 0 from CategoryEntity c
           where lower(c.name) = lower(:name) and (:excludeId is null or c.id <> :excludeId)"""
    )
    fun existsByNameIgnoreCase(@Param("name") name: String, @Param("excludeId") excludeId: Long?): Boolean

    fun existsByCode(code: String): Boolean

    /** (category name, keyword) for every active category other than [excludeId] that already uses one of [keywords]. */
    @Query(
        """select c.name, k from CategoryEntity c join c.keywords k
           where c.active = true and c.id <> :excludeId and k in :keywords
           order by c.name, k"""
    )
    fun findActiveKeywordClashes(
        @Param("keywords") keywords: Collection<String>,
        @Param("excludeId") excludeId: Long
    ): List<Array<Any>>

    /** Admin list. [pattern] is a lower-case LIKE pattern from CatalogText.containsPattern, or null. */
    @Query(
        value = """select c from CategoryEntity c
           where (:active is null or c.active = :active)
             and (:pattern is null
                  or lower(c.name) like :pattern escape '\'
                  or lower(c.code) like :pattern escape '\'
                  or lower(coalesce(c.description, '')) like :pattern escape '\'
                  or exists (select 1 from CategoryEntity c2 join c2.keywords k
                             where c2.id = c.id and k like :pattern escape '\'))""",
        countQuery = """select count(c) from CategoryEntity c
           where (:active is null or c.active = :active)
             and (:pattern is null
                  or lower(c.name) like :pattern escape '\'
                  or lower(c.code) like :pattern escape '\'
                  or lower(coalesce(c.description, '')) like :pattern escape '\'
                  or exists (select 1 from CategoryEntity c2 join c2.keywords k
                             where c2.id = c.id and k like :pattern escape '\'))"""
    )
    fun search(
        @Param("active") active: Boolean?,
        @Param("pattern") pattern: String?,
        pageable: Pageable
    ): Page<CategoryEntity>
}
