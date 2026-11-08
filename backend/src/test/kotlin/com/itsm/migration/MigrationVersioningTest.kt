package com.itsm.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Cheap, always-on guard for the migration plan in docs/migrations.md:
 *  - no two files share a version (the classic merge collision), and
 *  - every version falls in a block reserved for one teammate.
 * It cannot prove FK ordering or SQL validity; CI should additionally run all migrations
 * from an empty PostgreSQL 16 (see docs/migrations.md).
 */
class MigrationVersioningTest {
    // 1 = boilerplate baseline; P1 10-19; P2 20-29; P3 30-39; P4 40-49
    private val blocks = listOf(1..1, 10..19, 20..29, 30..39, 40..49)
    private val pattern = Regex("^V(\\d+)((?:[._]\\d+)*)__[A-Za-z0-9_]+\\.sql$")

    private val files = PathMatchingResourcePatternResolver()
        .getResources("classpath*:db/migration/*")
        .mapNotNull { it.filename }

    @Test fun `at least the baseline migration is discovered`() = assertTrue(files.any { it.startsWith("V1__") })

    @Test fun `file names follow Flyway versioned naming`() {
        val bad = files.filterNot { pattern.matches(it) }
        assertTrue(bad.isEmpty(), "Unexpected migration file names: $bad")
    }

    @Test fun `no two migrations share a version`() {
        val versions = files.mapNotNull { pattern.matchEntire(it) }.map { it.groupValues[1] + it.groupValues[2].replace('_', '.') }
        val duplicates = versions.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals(emptySet<String>(), duplicates, "Duplicate migration versions")
    }

    @Test fun `every version is inside a reserved block`() {
        val outside = files.mapNotNull { pattern.matchEntire(it) }
            .map { it.groupValues[1].toInt() }
            .filter { v -> blocks.none { v in it } }
        assertTrue(outside.isEmpty(), "Versions outside reserved blocks: $outside — see docs/migrations.md")
    }
}
