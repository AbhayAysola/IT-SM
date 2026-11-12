package com.itsm.user

import com.itsm.common.model.Role
import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDateTime

/**
 * Canonical user row (V1 + V15). `role` is stored as one of the canonical [Role] names; read it through [Role.fromStored].
 * `tokenVersion` is embedded in every JWT: bumping it (password reset, role change, deactivation, explicit revoke)
 * invalidates all tokens issued before.
 */
@Entity
@Table(name = "users")
class UserEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(unique = true, nullable = false)
    var email: String = "",
    @Column(nullable = false)
    var password: String = "",
    @Column(nullable = false)
    var name: String = "",
    @Column(nullable = false)
    var role: String = Role.EMPLOYEE.name,
    @Column(nullable = false)
    var active: Boolean = true,
    @Column(name = "token_version", nullable = false)
    var tokenVersion: Int = 0,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

/** Login/registration response user. Never contains the password hash or token version. */
data class UserDto(
    val id: Long = 0,
    val email: String = "",
    val name: String = "",
    val role: String = "",
    val active: Boolean = true,
    val createdAt: String = ""
)

/** The role is always the canonical name; a corrupt stored value surfaces as IllegalStateException. */
fun UserEntity.toDto() = UserDto(
    id = id,
    email = email,
    name = name,
    role = Role.fromStored(role).name,
    active = active,
    createdAt = createdAt.toString()
)

object EmailNormalizer {
    fun normalize(email: String): String = email.trim().lowercase(java.util.Locale.ROOT)
}
