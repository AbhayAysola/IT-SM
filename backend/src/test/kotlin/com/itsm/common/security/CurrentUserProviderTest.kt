package com.itsm.common.security

import com.itsm.common.error.AuthenticationFailed
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.model.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder

class CurrentUserProviderTest {
    private val provider = SecurityContextCurrentUserProvider()

    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    @Test fun `resolves id, email and role from the principal`() {
        val d = ItsmUserDetails(42, "t@x.com", "hash", Role.TECHNICIAN)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(d, null, d.authorities)
        assertEquals(CurrentUser(42, "t@x.com", Role.TECHNICIAN), provider.current())
    }

    @Test fun `no authentication yields null and current() throws 401`() {
        assertNull(provider.currentOrNull())
        assertThrows<AuthenticationFailed> { provider.current() }
    }

    @Test fun `anonymous token is not a user`() {
        SecurityContextHolder.getContext().authentication =
            AnonymousAuthenticationToken("k", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
        assertNull(provider.currentOrNull())
    }

    @Test fun `foreign principal type is a wiring bug`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("someone", null, AuthorityUtils.createAuthorityList("ROLE_EMPLOYEE"))
        assertThrows<IllegalStateException> { provider.currentOrNull() }
    }

    @Test fun `requirePermission enforces the role mapping`() {
        val employee = CurrentUser(1, "e@x.com", Role.EMPLOYEE)
        employee.requirePermission(Permission.INCIDENT_CREATE)
        assertThrows<UnauthorizedOperation> { employee.requirePermission(Permission.USER_MANAGE) }
    }

    @Test fun `id must be positive`() {
        assertThrows<IllegalArgumentException> { CurrentUser(0, "e@x.com", Role.EMPLOYEE) }
    }

    @Test fun `principal never prints credentials`() {
        val d = ItsmUserDetails(1, "e@x.com", "super-secret-hash", Role.EMPLOYEE)
        assert(!d.toString().contains("super-secret-hash") && !d.toString().contains("e@x.com"))
        d.eraseCredentials()
        assertNull(d.password)
    }
}
