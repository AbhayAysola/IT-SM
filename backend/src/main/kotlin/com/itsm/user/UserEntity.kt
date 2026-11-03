package com.itsm.user

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class UserEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(unique = true, nullable = false)
    val email: String = "",
    @Column(nullable = false)
    var password: String = "",
    @Column(nullable = false)
    val name: String = "",
    @Column(nullable = false)
    val role: String = "USER",
    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)

data class UserDto(
    val id: Long = 0,
    val email: String = "",
    val name: String = "",
    val role: String = "",
    val createdAt: String = ""
)

fun UserEntity.toDto() = UserDto(
    id = id,
    email = email,
    name = name,
    role = role,
    createdAt = createdAt.toString()
)
