package com.itsm.common.security

import com.itsm.common.model.Role
import org.springframework.security.core.CredentialsContainer
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

/**
 * Principal carrying the user id and role, so downstream code never hits the DB for identity.
 *
 * Compatible with the existing auth flow: `username` is still the email and the token
 * is still keyed by email. Authorities = `ROLE_<role>` plus one authority per [Permission].
 */
class ItsmUserDetails(
    val id: Long,
    val email: String,
    passwordHash: String,
    val role: Role
) : UserDetails, CredentialsContainer {

    private var passwordHash: String? = passwordHash
    private val grantedAuthorities: List<GrantedAuthority> =
        (listOf(SimpleGrantedAuthority(role.authority)) +
            RolePermissions.of(role).map { SimpleGrantedAuthority(it.name) })

    fun toCurrentUser(): CurrentUser = CurrentUser(id, email, role)

    override fun getUsername(): String = email
    override fun getPassword(): String? = passwordHash
    override fun getAuthorities(): Collection<GrantedAuthority> = grantedAuthorities
    override fun isAccountNonExpired() = true
    override fun isAccountNonLocked() = true      // lockout arrives with auth hardening
    override fun isCredentialsNonExpired() = true
    override fun isEnabled() = true               // `active` flag arrives with auth hardening
    override fun eraseCredentials() { passwordHash = null }

    override fun equals(other: Any?) = other is ItsmUserDetails && other.email == email
    override fun hashCode() = email.hashCode()
    override fun toString() = "ItsmUserDetails(id=$id, role=$role)"   // never print hash or email
}
