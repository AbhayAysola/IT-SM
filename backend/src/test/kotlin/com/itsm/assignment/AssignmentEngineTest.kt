package com.itsm.assignment

import com.itsm.common.spi.AssignmentStrategyCatalog
import com.itsm.common.spi.TechnicianWorkloadProvider
import com.itsm.lifecycle.FakeIncidentStore
import com.itsm.lifecycle.IncidentStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.time.Instant

class AssignmentEngineTest {
    private val strategies = listOf(LoadBasedStrategy(), SkillBasedStrategy(), RoundRobinStrategy(), ManualStrategy())
    private val engine = AssignmentEngine(strategies, TechnicianRecommender())
    private val pool = listOf(cand(1, active = 3), cand(2, active = 1), cand(3, active = 2))

    @Test fun `the default load-based setting assigns the least loaded technician`() {
        val d = engine.decide(settings(), req(), pool)
        assertEquals(AssignmentOutcome.ASSIGNED, d.outcome)
        assertEquals(2L, d.chosen?.userId)
        assertEquals("LOAD_BASED", d.strategyKey)
        assertNull(d.fellBackFrom)
        assertEquals(3, d.recommendations.size)
    }

    @Test fun `a category override beats the global strategy`() {
        val s = settings(strategy = "LOAD_BASED", overrides = mapOf(CAT to "ROUND_ROBIN"))
        val c = listOf(cand(1, last = Instant.parse("2026-11-01T00:00:00Z")), cand(2, last = Instant.parse("2026-10-01T00:00:00Z")))
        assertEquals("ROUND_ROBIN", engine.decide(s, req(), c).strategyKey)
        assertEquals(2L, engine.decide(s, req(), c).chosen?.userId)
        // a different category still uses the global strategy
        assertEquals("LOAD_BASED", engine.decide(s, req(category = 8L), c).strategyKey)
    }

    @Test fun `the manual strategy returns recommendations and chooses nobody`() {
        val d = engine.decide(settings("MANUAL"), req(), pool)
        assertEquals(AssignmentOutcome.MANUAL_REQUIRED, d.outcome)
        assertNull(d.chosen)
        assertTrue(d.recommendations.isNotEmpty())
    }

    @Test fun `nobody eligible gives NO_ELIGIBLE_TECHNICIAN, not an exception`() {
        val c = listOf(cand(1, available = false), cand(2, active = 5, max = 5))
        val d = engine.decide(settings(), req(), c)
        assertEquals(AssignmentOutcome.NO_ELIGIBLE_TECHNICIAN, d.outcome)
        assertNull(d.chosen)
        assertTrue(d.recommendations.isEmpty())
    }

    @Test fun `skill based with no skilled technician reports none, even though others are eligible`() {
        val d = engine.decide(settings("SKILL_BASED"), req(), pool)
        assertEquals(AssignmentOutcome.NO_ELIGIBLE_TECHNICIAN, d.outcome)
        assertEquals(3, d.recommendations.size) // advice is still offered for a person to act on
    }

    @Test fun `an unregistered key falls back to LOAD_BASED and says so`() {
        val d = engine.decide(settings("FASTEST_FINGER"), req(), pool)
        assertEquals(AssignmentOutcome.ASSIGNED, d.outcome)
        assertEquals("LOAD_BASED", d.strategyKey)
        assertEquals("FASTEST_FINGER", d.fellBackFrom)
    }

    @Test fun `reassignment never picks an excluded technician`() {
        val d = engine.decide(settings(), req(exclude = setOf(2L)), pool)
        assertEquals(3L, d.chosen?.userId)
        assertTrue(d.recommendations.none { it.technician.userId == 2L })
    }

    @Test fun `a technician at capacity is skipped even when the most skilled`() {
        val expert = cand(1, active = 5, max = 5, years = 20, skills = mapOf(CAT to 5))
        val capable = cand(2, active = 4, max = 5, skills = mapOf(CAT to 2))
        assertEquals(2L, engine.decide(settings("SKILL_BASED"), req(), listOf(expert, capable)).chosen?.userId)
    }

    @Test fun `the same inputs always give the same decision`() {
        assertEquals(engine.decide(settings(), req(), pool), engine.decide(settings(), req(), pool.reversed()))
    }

    // ---- construction rules
    @Test fun `duplicate keys are rejected`() {
        assertThrows<IllegalArgumentException> { AssignmentEngine(strategies + LoadBasedStrategy(), TechnicianRecommender()) }
    }

    @Test fun `the application cannot start without the seeded default`() {
        val e = assertThrows<IllegalArgumentException> { AssignmentEngine(listOf(RoundRobinStrategy()), TechnicianRecommender()) }
        assertTrue(e.message!!.contains("LOAD_BASED"))
    }

    @Test fun `keys must be UPPER_SNAKE_CASE`() {
        val bad = object : AssignmentStrategy {
            override val key = "fast-lane"
            override fun rank(candidates: List<Candidate>, request: AssignmentRequest, defaultMaxWorkload: Int) = candidates
        }
        assertThrows<IllegalArgumentException> { AssignmentEngine(strategies + bad, TechnicianRecommender()) }
    }

    @Test fun `a new strategy is picked up with no other change`() {
        val newest = object : AssignmentStrategy {
            override val key = "HIGHEST_ID"
            override fun rank(candidates: List<Candidate>, request: AssignmentRequest, defaultMaxWorkload: Int) =
                candidates.sortedByDescending { it.id }
        }
        val e = AssignmentEngine(strategies + newest, TechnicianRecommender())
        assertEquals(3L, e.decide(settings("HIGHEST_ID"), req(), pool).chosen?.userId)
    }

    // ---- Spring wiring + SPI
    @Test fun `component scanning wires the engine and exposes the SPI`() {
        // The workload provider in this package reads incidents through the store, which another package provides.
        val ctx = AnnotationConfigApplicationContext().apply {
            registerBean(IncidentStore::class.java, java.util.function.Supplier<IncidentStore> { FakeIncidentStore() })
            scan("com.itsm.assignment")
            refresh()
        }
        ctx.use {
            assertTrue(ctx.getBean(TechnicianWorkloadProvider::class.java) is StoreTechnicianWorkloadProvider)
            val catalog = ctx.getBean(AssignmentStrategyCatalog::class.java)
            assertEquals(setOf("LOAD_BASED", "SKILL_BASED", "ROUND_ROBIN", "MANUAL"), catalog.registeredKeys())
            assertTrue(catalog.isRegistered("LOAD_BASED"))
            assertTrue(!catalog.isRegistered("load_based"))
            assertTrue(!catalog.isRegistered("NOPE"))
        }
    }
}
