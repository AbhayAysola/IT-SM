package com.itsm.auth.ratelimit

import java.time.Clock
import java.time.Duration

/**
 * A fixed-window attempt counter over a BOUNDED set of keys. Thread-safe (one lock; every operation is O(1) amortised).
 *
 * Window: a key's window starts at its first counted hit and lasts [window]; the count then restarts. A key is *blocked*
 * while its live count has reached [limit]; blocked hits are refused and are NOT counted, so hammering a blocked key does
 * not extend its lockout.
 *
 * Memory: at most [capacity] keys. TTL: expired entries are swept whenever the map fills and every [SWEEP_EVERY] new keys.
 * Saturation, defined (never "stop counting"):
 *  1. expired entries are dropped first;
 *  2. if the map is still full, the least-recently-used entry that is NOT blocked is evicted (it only loses a partial count);
 *  3. a BLOCKED entry is never evicted to make room: if every entry is blocked, a NEW key is refused (fail closed) until
 *     the earliest lockout expires. So flooding the map with unique keys cannot lift an active lockout and cannot
 *     disable the limiter.
 *
 * Counters are per application instance: with N replicas the effective limit is up to N times [limit].
 */
class BoundedWindowCounter(
    private val limit: Int,
    private val window: Duration,
    private val capacity: Int,
    private val clock: Clock
) {
    init {
        require(limit >= 1) { "limit must be >= 1" }
        require(!window.isNegative && !window.isZero) { "window must be positive" }
        require(capacity >= 1) { "capacity must be >= 1" }
    }

    private class Entry(var count: Int, var windowEndMs: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(256, 0.75f, true)   // access order: eldest = least recently used
    private var newKeysSinceSweep = 0

    private fun nowMs() = clock.millis()
    private fun Entry.live(now: Long) = now < windowEndMs
    private fun Entry.blocked(now: Long) = live(now) && count >= limit
    private fun retrySeconds(endMs: Long, now: Long) = maxOf(1L, (endMs - now + 999) / 1000)

    /**
     * Counts one attempt for [key]. Returns 0 when it is allowed (and counted), otherwise the number of seconds until the
     * key may try again (the attempt is refused and not counted).
     */
    fun hit(key: String): Long = synchronized(lock) {
        val now = nowMs()
        val entry = entries[key]
        if (entry != null) {
            if (!entry.live(now)) {
                entry.count = 1; entry.windowEndMs = now + window.toMillis(); return 0
            }
            if (entry.count >= limit) return retrySeconds(entry.windowEndMs, now)
            entry.count++
            return 0
        }
        // a new key needs a slot
        if (entries.size >= capacity || newKeysSinceSweep >= SWEEP_EVERY) sweepExpired(now)
        if (entries.size >= capacity && !evictOneUnblocked(now)) {
            return earliestExpirySeconds(now)           // saturated with active lockouts: fail closed
        }
        entries[key] = Entry(1, now + window.toMillis())
        newKeysSinceSweep++
        0
    }

    /** Takes back one counted attempt (used when an attempt turns out not to be a failure). Never goes below zero. */
    fun refund(key: String) = synchronized(lock) {
        val entry = entries[key] ?: return
        if (entry.live(nowMs()) && entry.count > 0) entry.count--
    }

    /** Forgets [key] entirely (a success clears its failure history). */
    fun reset(key: String) = synchronized(lock) { entries.remove(key); Unit }

    /** Entries currently held (expired ones count until swept). For tests and metrics. */
    fun size(): Int = synchronized(lock) { entries.size }

    private fun sweepExpired(now: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) if (!it.next().value.live(now)) it.remove()
        newKeysSinceSweep = 0
    }

    private fun evictOneUnblocked(now: Long): Boolean {
        val it = entries.entries.iterator()          // eldest (least recently used) first
        while (it.hasNext()) {
            if (!it.next().value.blocked(now)) { it.remove(); return true }
        }
        return false
    }

    private fun earliestExpirySeconds(now: Long): Long =
        retrySeconds(entries.values.minOf { it.windowEndMs }, now)

    private companion object {
        const val SWEEP_EVERY = 1024
    }
}
