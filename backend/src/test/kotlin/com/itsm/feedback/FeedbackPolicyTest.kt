package com.itsm.feedback

import com.itsm.common.error.DuplicateResource
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.model.Role
import com.itsm.common.security.CurrentUser
import com.itsm.lifecycle.IncidentState
import com.itsm.lifecycle.IncidentState.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class FeedbackPolicyTest {
    private val reporter = CurrentUser(3, "e@campus.example", Role.EMPLOYEE)
    private val stranger = CurrentUser(4, "x@campus.example", Role.EMPLOYEE)
    private val admin = CurrentUser(1, "a@campus.example", Role.ADMIN)
    private val tech = CurrentUser(2, "t@campus.example", Role.TECHNICIAN)
    private val now = Instant.parse("2026-11-11T10:00:00Z")

    private fun submit(state: IncidentState = VERIFIED, who: CurrentUser = reporter, reporterId: Long = reporter.id,
                       existing: Feedback? = null, rating: Int = 4, comment: String? = "quick fix") =
        FeedbackPolicy.submit("INC1024", state, reporterId, who, existing, rating, comment, now)

    @Test fun `the reporter can give feedback once the fix is verified or the incident closed`() {
        for (s in listOf(VERIFIED, CLOSED)) {
            val f = submit(s)
            assertEquals(Feedback("INC1024", 3, 4, "quick fix", now), f)
        }
    }

    @Test fun `feedback is refused in every earlier state with 409`() {
        for (s in IncidentState.entries - setOf(VERIFIED, CLOSED)) {
            val e = assertThrows<FeedbackNotAllowed>("$s") { submit(s) }
            assertEquals(ErrorCode.CONFLICT, e.code)
        }
    }

    @Test fun `only the reporter can give feedback, not another employee, a technician or even an admin`() {
        for (who in listOf(stranger, tech, admin)) assertThrows<UnauthorizedOperation>("${who.role}") { submit(who = who) }
    }

    /**
     * KNOWN GAP in common/RolePermissions (P1): TECHNICIAN can create incidents but lacks INCIDENT_VERIFY_OWN,
     * so a technician who reports their own incident can neither verify nor rate it. This test pins today's
     * behaviour; when P1 grants the permission, flip it to expect success (see docs/architecture/p3-feedback.md).
     */
    @Test fun `known gap - a technician who reported the incident cannot rate it yet`() {
        assertThrows<UnauthorizedOperation> { submit(who = tech, reporterId = tech.id) }
    }

    @Test fun `authorisation is checked before state so a stranger learns nothing about the incident`() {
        assertThrows<UnauthorizedOperation> { submit(state = NEW, who = stranger) }
    }

    @Test fun `second feedback is a 409 duplicate, because feedback is final`() {
        val first = submit()
        assertEquals(ErrorCode.DUPLICATE_RESOURCE, assertThrows<DuplicateResource> { submit(existing = first) }.code)
    }

    @Test fun `state is checked before duplicate and both before input`() {
        assertThrows<FeedbackNotAllowed> { submit(state = RESOLVED, existing = submit(), rating = 99) }
        assertThrows<DuplicateResource> { submit(existing = submit(), rating = 99) }
    }

    @Test fun `ratings outside 1 to 5 are a field error`() {
        for (bad in listOf(0, 6, -1, 100)) {
            assertEquals("rating", assertThrows<InvalidInput>("$bad") { submit(rating = bad) }.fieldErrors.single().field)
        }
        for (ok in 1..5) assertEquals(ok, submit(rating = ok).rating)
    }

    @Test fun `the comment is optional, trimmed, and blank means none`() {
        assertNull(submit(comment = null).comment)
        assertNull(submit(comment = "   ").comment)
        assertEquals("fine", submit(comment = "  fine ").comment)
    }

    @Test fun `an over-long comment is a field error and the limit itself is accepted`() {
        assertEquals("comment", assertThrows<InvalidInput> { submit(comment = "x".repeat(1001)) }.fieldErrors.single().field)
        assertEquals(1000, submit(comment = "x".repeat(1000)).comment!!.length)
    }

    @Test fun `every invalid field is reported in one response`() {
        val e = assertThrows<InvalidInput> { submit(rating = 9, comment = "x".repeat(1001)) }
        assertEquals(setOf("rating", "comment"), e.fieldErrors.map { it.field }.toSet())
    }

    @Test fun `the entity guards its own invariants`() {
        assertThrows<IllegalArgumentException> { Feedback("INC1", 1, 0, null, now) }
        assertThrows<IllegalArgumentException> { Feedback("INC1", 1, 3, "  ", now) }
        assertThrows<IllegalArgumentException> { Feedback("bad id", 1, 3, null, now) }
    }
}
