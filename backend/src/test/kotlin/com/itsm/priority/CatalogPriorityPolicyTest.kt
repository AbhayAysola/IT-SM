package com.itsm.priority

import com.itsm.common.error.ServiceNotFound
import com.itsm.common.model.Impact
import com.itsm.common.model.Priority
import com.itsm.common.model.Urgency
import com.itsm.common.spi.ServiceCatalog
import com.itsm.common.spi.ServiceView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.annotation.Transactional

class CatalogPriorityPolicyTest {

    private fun svc(id: Long, criticality: Int = 3, active: Boolean = true, dependsOn: Set<Long> = emptySet()) =
        ServiceView(id, "Service $id", null, criticality, null, active, dependsOn)

    /** In-memory catalog: only what the policy uses; counts calls so the read budget is pinned. */
    private class FakeCatalog(all: List<ServiceView>) : ServiceCatalog {
        private val byId = all.associateBy { it.id }
        var listActiveCalls = 0
        override fun findById(serviceId: Long) = byId[serviceId]
        override fun listActive(): List<ServiceView> = byId.values.filter { it.active }.also { listActiveCalls++ }
        override fun dependentsOf(serviceId: Long): Set<Long> = error("the policy must not walk the graph one query per node")
    }

    @Test fun `it assesses with the service's criticality and transitive active dependents`() {
        // 2 and 3 depend on 1; 4 depends on 3: three dependents of 1 in total
        val catalog = FakeCatalog(listOf(svc(1, criticality = 5), svc(2, dependsOn = setOf(1)), svc(3, dependsOn = setOf(1)), svc(4, dependsOn = setOf(3))))
        val a = CatalogPriorityPolicy(catalog).assess(1, Impact.LOW, Urgency.HIGH)
        assertEquals(3, a.dependentServiceCount)
        assertEquals(Impact.MEDIUM, a.effectiveImpact)
        assertEquals(Priority.P1, a.priority) // MEDIUM/HIGH = P2, criticality 5 -> P1
        assertEquals(5, a.businessCriticality)
        assertTrue(a.reasons.any { "Service 1" in it })
        assertEquals(1, catalog.listActiveCalls)
    }

    @Test fun `an unknown service is ServiceNotFound`() {
        assertThrows<ServiceNotFound> { CatalogPriorityPolicy(FakeCatalog(emptyList())).assess(9, Impact.LOW, Urgency.LOW) }
    }

    @Test fun `an inactive service is still assessed`() {
        val a = CatalogPriorityPolicy(FakeCatalog(listOf(svc(1, criticality = 1, active = false)))).assess(1, Impact.HIGH, Urgency.HIGH)
        assertEquals(Priority.P2, a.priority)
    }

    @Test fun `ServiceNotFound does not doom the caller's transaction`() {
        val tx = CatalogPriorityPolicy::class.java.getAnnotation(Transactional::class.java)
        assertTrue(tx.readOnly)
        assertTrue(ServiceNotFound::class in tx.noRollbackFor)
    }

    @Test fun `the walk follows chains, counts a diamond once and never counts the service itself`() {
        val all = listOf(
            svc(1), svc(2, dependsOn = setOf(1)), svc(3, dependsOn = setOf(1)),
            svc(4, dependsOn = setOf(2, 3)), // diamond: reachable twice, counted once
            svc(5, dependsOn = setOf(4)), svc(9)
        )
        assertEquals(setOf(2L, 3L, 4L, 5L), CatalogPriorityPolicy.transitiveActiveDependents(1, all))
        assertEquals(setOf(4L, 5L), CatalogPriorityPolicy.transitiveActiveDependents(2, all))
        assertEquals(emptySet<Long>(), CatalogPriorityPolicy.transitiveActiveDependents(9, all))
    }

    @Test fun `a cycle terminates and excludes the starting service`() {
        val all = listOf(svc(1, dependsOn = setOf(3)), svc(2, dependsOn = setOf(1)), svc(3, dependsOn = setOf(2)))
        assertEquals(setOf(2L, 3L), CatalogPriorityPolicy.transitiveActiveDependents(1, all))
    }

    @Test fun `an inactive service neither counts nor carries impact through`() {
        // 2 (inactive) depends on 1; 3 depends on 2. Only active services are listed, so neither 2 nor 3 counts.
        val catalog = FakeCatalog(listOf(svc(1), svc(2, active = false, dependsOn = setOf(1)), svc(3, dependsOn = setOf(2)), svc(4, dependsOn = setOf(1))))
        assertEquals(setOf(4L), CatalogPriorityPolicy.transitiveActiveDependents(1, catalog.listActive()))
    }
}
