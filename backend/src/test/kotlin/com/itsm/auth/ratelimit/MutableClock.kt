package com.itsm.auth.ratelimit

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong

/** A clock the test moves by hand, so no rate-limit test ever sleeps or depends on wall-clock time. */
class MutableClock(start: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    private val millis = AtomicLong(start.toEpochMilli())
    fun advance(d: Duration) { millis.addAndGet(d.toMillis()) }
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(millis.get())
    override fun millis(): Long = millis.get()
}
