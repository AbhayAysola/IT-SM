package com.itsm.assignment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class StrategiesTest {
    private val load = LoadBasedStrategy()
    private val skill = SkillBasedStrategy()
    private val rr = RoundRobinStrategy()
    private val manual = ManualStrategy()

    // ---- load based
    @Test fun `load based compares utilisation, not raw counts`() {
        // 1 of 10 (10%) beats 1 of 2 (50%) even though both have one incident
        assertEquals(listOf(1L, 2L), load.rank(listOf(cand(2, active = 1, max = 2), cand(1, active = 1, max = 10)), req(), 5).ids())
    }

    @Test fun `load based tie-breaks are fewer absolute, more experience, lowest id`() {
        // 1/2 and 2/4 have equal utilisation: fewer absolute wins
        assertEquals(listOf(1L, 2L), load.rank(listOf(cand(2, 2, 4), cand(1, 1, 2)), req(), 5).ids())
        // identical load: more experience wins, then id
        val c = listOf(cand(3, 1, years = 2), cand(2, 1, years = 9), cand(1, 1, years = 9))
        assertEquals(listOf(1L, 2L, 3L), load.rank(c, req(), 5).ids())
    }

    @Test fun `a technician without their own limit is compared using the default`() {
        // id 1 has no limit (0) so the default 10 applies: 2/10 beats id 2's 2/4
        assertEquals(listOf(1L, 2L), load.rank(listOf(cand(2, 2, max = 4), cand(1, 2, max = 0)), req(), 10).ids())
    }

    @Test fun `ranking does not depend on input order`() {
        val c = listOf(cand(1, 3), cand(2, 1), cand(3, 2), cand(4, 1, years = 8))
        assertEquals(load.rank(c, req(), 5).ids(), load.rank(c.reversed(), req(), 5).ids())
    }

    // ---- skill based
    @Test fun `skill based ranks by proficiency and drops the unskilled`() {
        val c = listOf(
            cand(1, skills = mapOf(CAT to 2)), cand(2, skills = mapOf(CAT to 5)),
            cand(3, skills = mapOf(99L to 5)), cand(4)
        )
        assertEquals(listOf(2L, 1L), skill.rank(c, req(), 5).ids())
    }

    @Test fun `skill based breaks proficiency ties by load then experience then id`() {
        val s = mapOf(CAT to 4)
        val c = listOf(cand(3, 2, skills = s), cand(2, 1, years = 1, skills = s), cand(1, 1, years = 8, skills = s))
        assertEquals(listOf(1L, 2L, 3L), skill.rank(c, req(), 5).ids())
    }

    @Test fun `skill based with no category keeps everyone in load order`() {
        val c = listOf(cand(2, 3), cand(1, 1))
        assertEquals(listOf(1L, 2L), skill.rank(c, req(category = null), 5).ids())
    }

    @Test fun `skill based returns nobody when nobody is skilled`() {
        assertTrue(skill.rank(listOf(cand(1), cand(2)), req(), 5).isEmpty())
    }

    // ---- round robin
    @Test fun `round robin serves never-assigned first, then the longest waiting`() {
        val t0 = Instant.parse("2026-11-01T00:00:00Z")
        val c = listOf(
            cand(1, last = t0.plusSeconds(100)), cand(2, last = t0), cand(4), cand(3)
        )
        assertEquals(listOf(3L, 4L, 2L, 1L), rr.rank(c, req(), 5).ids())
    }

    @Test fun `round robin rotates as assignments are recorded`() {
        var pool = listOf(cand(1), cand(2), cand(3))
        val order = mutableListOf<Long>()
        var clock = Instant.parse("2026-11-01T00:00:00Z")
        repeat(6) {
            val next = rr.rank(pool, req(), 5).first().id
            order += next
            clock = clock.plusSeconds(60)
            pool = pool.map { if (it.id == next) it.copy(lastAssignedAt = clock) else it }
        }
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), order)
    }

    @Test fun `round robin ignores load and skill`() {
        val c = listOf(cand(1, active = 4), cand(2, active = 0))
        assertEquals(listOf(1L, 2L), rr.rank(c, req(), 5).ids())
    }

    // ---- manual
    @Test fun `manual never auto-assigns and the others do`() {
        assertFalse(manual.autoAssigns)
        assertTrue(load.autoAssigns && skill.autoAssigns && rr.autoAssigns)
    }

    @Test fun `built-in keys are the documented ones`() {
        assertEquals(setOf("LOAD_BASED", "SKILL_BASED", "ROUND_ROBIN", "MANUAL"), listOf(load, skill, rr, manual).map { it.key }.toSet())
    }
}
