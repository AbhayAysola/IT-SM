package com.itsm.user

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : JpaRepository<UserEntity, Long> {
    /** [email] must already be normalised ([EmailNormalizer]); stored emails are lower-case. */
    fun findByEmail(email: String): UserEntity?

    fun existsByEmail(email: String): Boolean

    fun countByRoleAndActiveTrue(role: String): Long

    fun existsByRole(role: String): Boolean

    fun findByRoleAndActiveTrueOrderByIdAsc(role: String): List<UserEntity>

    /** [pattern] is a lower-case LIKE pattern (see CatalogText.containsPattern) or null. */
    @Query(
        """select u from UserEntity u
           where (:role is null or u.role = :role)
             and (:active is null or u.active = :active)
             and (:pattern is null or lower(u.email) like :pattern escape '\' or lower(u.name) like :pattern escape '\')"""
    )
    fun search(
        @Param("pattern") pattern: String?,
        @Param("role") role: String?,
        @Param("active") active: Boolean?,
        pageable: Pageable
    ): Page<UserEntity>
}
