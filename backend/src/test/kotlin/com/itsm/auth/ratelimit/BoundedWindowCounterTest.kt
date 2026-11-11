package com.itsm.auth.ratelimit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BoundedWindowCounterTest {

    private val clock = MutableClock()
    private val window = Duration.ofMinutes(15)
    private fun counter(limit: Int = 3, capacity: Int = 100) = BoundedWindowCounter(limit, window, capacity, clock)

    @Test fun `a key is allowed up to its limit and then refused with the seconds until the window ends`() {
        val c = counter(limit = 3)
        repeat(3) { assertEquals(0, c.hit("k")) }
        assertEquals(900, c.hit("k"))                         // 15 min, nothing has elapsed
        clock.advance(Duration.ofMinutes(5))
        assertEquals(600, c.hit("k"))
    }

    @Test fun `refused hits are not counted and do not extend the lockout`() {
        val c = counter(limit = 2)
        c.hit("k"); c.hit("k")
        repeat(50) { clock.advance(Duration.ofSeconds(1)); assertTrue(c.hit("k") > 0) }
        clock.advance(Duration.ofMinutes(15) - Duration.ofSeconds(50))        // 15 minutes after the FIRST hit
        assertEquals(0, c.hit("k"), "the window ended on schedule, hammering did not push it back")
    }

    @Test fun `the window expires and the count restarts`() {
        val c = counter(limit = 2)
        c.hit("k"); c.hit("k"); assertTrue(c.hit("k") > 0)
        clock.advance(Duration.ofMinutes(15))
        assertEquals(0, c.hit("k")); assertEquals(0, c.hit("k")); assertTrue(c.hit("k") > 0)
    }

    @Test fun `keys are independent`() {
        val c = counter(limit = 1)
        assertEquals(0, c.hit("a")); assertTrue(c.hit("a") > 0)
        assertEquals(0, c.hit("b"))
    }

    @Test fun `refund gives an attempt back and never goes below zero, reset forgets the key`() {
        val c = counter(limit = 2)
        c.hit("k"); c.hit("k"); assertTrue(c.hit("k") > 0)
        c.refund("k"); assertEquals(0, c.hit("k"))
        repeat(5) { c.refund("k") }                              // below zero is clamped
        assertEquals(0, c.hit("k")); assertEquals(0, c.hit("k")); assertTrue(c.hit("k") > 0)
        c.reset("k"); assertEquals(0, c.hit("k"))
        c.refund("never-seen")                                   // harmless
    }

    // --- capacity, eviction, saturation --------------------------------------------------------------------------------

    @Test fun `the number of keys never exceeds the capacity`() {
        val c = counter(limit = 5, capacity = 50)
        repeat(10_000) { c.hit("k$it") }
        assertTrue(c.size() <= 50, "size ${c.size()}")
    }

    @Test fun `expired entries are dropped before anything live is evicted`() {
        val c = counter(limit = 2, capacity = 3)
        c.hit("old1"); c.hit("old2"); c.hit("old3")
        clock.advance(Duration.ofMinutes(16))
        c.hit("fresh1"); c.hit("fresh2"); c.hit("fresh2")        // fresh2 is now at its limit: blocked
        assertTrue(c.hit("fresh2") > 0, "a live count survived because only expired entries were swept")
    }

    @Test fun `when full the least recently used unblocked entry goes, a blocked one is never evicted`() {
        val c = counter(limit = 2, capacity = 3)
        c.hit("b"); c.hit("b")                                   // blocked
        c.hit("x"); c.hit("y")                                   // unblocked (count 1)
        c.hit("z")                                               // full: evicts the LRU unblocked one ("x"), never "b"
        assertTrue(c.hit("b") > 0, "the blocked key is still blocked")
        assertEquals(0, c.hit("x"), "x lost its partial count and starts again")
    }

    @Test fun `flooding the map with unique keys cannot lift an active lockout`() {
        val c = counter(limit = 1, capacity = 10)
        c.hit("victim")                                          // blocked from now on
        repeat(5_000) { c.hit("flood$it") }
        assertTrue(c.hit("victim") > 0, "the lockout survived the flood")
    }

    @Test fun `when every slot is an active lockout a new key is refused, not silently allowed`() {
        val c = counter(limit = 1, capacity = 4)
        repeat(4) { c.hit("locked$it") }
        val retry = c.hit("newcomer")
        assertTrue(retry > 0, "saturated with lockouts: fail closed")
        assertTrue(retry <= 900)
        clock.advance(Duration.ofMinutes(15))                    // the lockouts expire, capacity frees up
        assertEquals(0, c.hit("newcomer"))
    }

    // --- concurrency ---------------------------------------------------------------------------------------------------

    @Test fun `parallel hits on one key never allow more than the limit`() {
        val limit = 10
        val c = BoundedWindowCounter(limit, window, 1000, clock)
        val pool = Executors.newFixedThreadPool(32)
        val start = CountDownLatch(1)
        val allowed = AtomicInteger()
        val tasks = (1..500).map { pool.submit { start.await(); if (c.hit("shared") == 0L) allowed.incrementAndGet() } }
        start.countDown()
        tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(limit, allowed.get())
    }

    @Test fun `parallel hits on many keys stay within capacity and each key within its limit`() {
        val c = BoundedWindowCounter(3, window, 200, clock)
        val pool = Executors.newFixedThreadPool(16)
        val allowedPerKey = Array(20) { AtomicInteger() }
        val tasks = (1..2000).map { n -> pool.submit { val k = n % 20; if (c.hit("k$k") == 0L) allowedPerKey[k].incrementAndGet() } }
        tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        allowedPerKey.forEach { assertEquals(3, it.get()) }
        assertTrue(c.size() <= 200)
    }
}
