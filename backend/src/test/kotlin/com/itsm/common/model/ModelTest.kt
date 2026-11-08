package com.itsm.common.model

import com.itsm.common.error.InvalidInput
import com.itsm.common.security.Permission
import com.itsm.common.security.RolePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ModelTest {
    @Test fun `legacy USER role maps to EMPLOYEE`() = assertEquals(Role.EMPLOYEE, Role.fromStored("USER"))

    @Test fun `stored role parsing ignores case and whitespace`() = assertEquals(Role.ADMIN, Role.fromStored(" admin "))

    @Test fun `unknown stored role is a data error not a client error`() {
        assertThrows<IllegalStateException> { Role.fromStored("SUPERUSER") }
    }

    @Test fun `role authority has ROLE_ prefix`() = assertEquals("ROLE_TECHNICIAN", Role.TECHNICIAN.authority)

    @Test fun `priority parses codes case-insensitively`() = assertEquals(Priority.P1, Priority.fromCode("p1"))

    @Test fun `invalid priority is rejected as input error`() {
        assertThrows<InvalidInput> { Priority.fromCode("P9") }
    }

    @Test fun `P1 is more urgent than P4`() {
        assertTrue(Priority.P1.isMoreUrgentThan(Priority.P4))
        assertFalse(Priority.P4.isMoreUrgentThan(Priority.P1))
        assertFalse(Priority.P2.isMoreUrgentThan(Priority.P2))
    }

    @Test fun `employees cannot manage users but can create incidents`() {
        val p = RolePermissions.of(Role.EMPLOYEE)
        assertTrue(Permission.INCIDENT_CREATE in p)
        assertFalse(Permission.USER_MANAGE in p)
    }

    @Test fun `technicians can work incidents but not manage catalog`() {
        val p = RolePermissions.of(Role.TECHNICIAN)
        assertTrue(Permission.INCIDENT_WORK in p)
        assertFalse(Permission.CATALOG_MANAGE in p)
    }

    @Test fun `admins hold every permission`() =
        assertEquals(Permission.entries.toSet(), RolePermissions.of(Role.ADMIN))

    @Test fun `permission sets are immutable`() {
        @Suppress("UNCHECKED_CAST")
        val set = RolePermissions.of(Role.EMPLOYEE) as MutableSet<Permission>
        assertThrows<UnsupportedOperationException> { set.add(Permission.USER_MANAGE) }
    }
}
