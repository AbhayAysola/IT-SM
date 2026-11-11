package com.itsm.assignment

import com.itsm.common.model.Priority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TechnicianRecommenderTest {
    private val rec = TechnicianRecommender()

    @Test fun `weights sum to one for every priority`() {
        Priority.entries.forEach { p ->
            val w = rec.weightsFor(p)
            assertEquals(1.0, w.skill + w.headroom + w.experience, 1e-9)
        }
        assertThrows<IllegalArgumentException> { TechnicianRecommender.Weights(0.5, 0.5, 0.5) }
    }

    @Test fun `a perfect technician scores 100 and the worst scores 0`() {
        val best = cand(1, active = 0, max = 5, years = 10, skills = mapOf(CAT to 5))
        val worst = cand(2, active = 5, max = 5, years = 0, skills = emptyMap())
        val r = rec.recommend(listOf(best, worst), req(), 5)
        assertEquals(100.0, r.first { it.technician.userId == 1L }.score)
        assertEquals(0.0, r.first { it.technician.userId == 2L }.score)
    }

    @Test fun `scores stay within 0 to 100 and experience is capped at ten years`() {
        val r = rec.recommend(listOf(cand(1, years = 40, skills = mapOf(CAT to 5)), cand(2, years = 10, skills = mapOf(CAT to 5))), req(), 5)
        assertEquals(r[0].score, r[1].score)
        assertTrue(r.all { it.score in 0.0..100.0 })
    }

    @Test fun `urgency shifts the ranking from free capacity to experience`() {
        val veteranBusy = cand(1, active = 4, max = 5, years = 10, skills = mapOf(CAT to 3))
        val juniorFree = cand(2, active = 0, max = 5, years = 1, skills = mapOf(CAT to 3))
        assertEquals(1L, rec.recommend(listOf(veteranBusy, juniorFree), req(Priority.P1), 5).first().technician.userId)
        assertEquals(2L, rec.recommend(listOf(veteranBusy, juniorFree), req(Priority.P4), 5).first().technician.userId)
    }

    @Test fun `reasons are plain language and mention skill, load and experience`() {
        val r = rec.recommend(listOf(cand(1, active = 2, max = 5, years = 1, skills = mapOf(CAT to 4))), req(), 5).single()
        assertEquals(listOf("Skill 4 of 5 in this category", "2 of 5 active incidents", "1 year of experience"), r.reasons)
    }

    @Test fun `a missing skill is said so, and no category adds no skill line`() {
        assertEquals("No recorded skill in this category", rec.recommend(listOf(cand(1)), req(), 5).single().reasons.first())
        assertEquals(2, rec.recommend(listOf(cand(1)), req(category = null), 5).single().reasons.size)
    }

    @Test fun `with no category skill is neutral for everyone`() {
        val r = rec.recommend(listOf(cand(1, skills = mapOf(CAT to 5)), cand(2)), req(category = null), 5)
        assertEquals(r[0].score, r[1].score)
        assertEquals(listOf(1L, 2L), r.map { it.technician.userId }) // id breaks the tie
    }

    @Test fun `output is limited, best first, and stable under input order`() {
        val c = (1L..8L).map { cand(it, active = (it % 4).toInt(), years = it.toInt()) }
        val a = rec.recommend(c, req(), 5, limit = 3)
        assertEquals(3, a.size)
        assertEquals(a.map { it.technician.userId }, rec.recommend(c.reversed(), req(), 5, limit = 3).map { it.technician.userId })
        assertTrue(a.zipWithNext().all { (x, y) -> x.score >= y.score })
    }

    @Test fun `empty input gives no recommendations`() {
        assertTrue(rec.recommend(emptyList(), req(), 5).isEmpty())
    }
}
