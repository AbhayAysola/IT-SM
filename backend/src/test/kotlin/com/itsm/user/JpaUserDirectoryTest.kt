package com.itsm.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class JpaUserDirectoryTest {
    private val directory = JpaUserDirectory(mock(UserRepository::class.java))

    @Test fun `technician methods report no data and the availability flag says why`() {
        assertFalse(JpaUserDirectory.TECHNICIAN_PROFILES_AVAILABLE, "flip this only when B6 persists profiles")
        assertNull(directory.findTechnician(1)); assertEquals(emptyList<Any>(), directory.listAvailableTechnicians(null))
        assertEquals(emptyList<Any>(), directory.escalationChain(1))
    }
}
