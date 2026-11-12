package com.itsm.lifecycle

import com.itsm.assignment.AssignmentEngine
import com.itsm.assignment.AssignmentOutcome
import com.itsm.assignment.LoadBasedStrategy
import com.itsm.assignment.ManualStrategy
import com.itsm.assignment.RoundRobinStrategy
import com.itsm.assignment.SkillBasedStrategy
import com.itsm.assignment.TechnicianRecommender
import com.itsm.common.error.ErrorCode
import com.itsm.common.error.InvalidInput
import com.itsm.common.error.InvalidStateTransition
import com.itsm.common.error.ItsmException
import com.itsm.common.error.ResourceNotFound
import com.itsm.common.error.TechnicianUnavailable
import com.itsm.common.error.UnauthorizedOperation
import com.itsm.common.error.WorkloadExceeded
import com.itsm.common.spi.AssignmentSettingsProvider
import com.itsm.common.spi.AssignmentSettingsView
import com.itsm.common.spi.UserDirectory
import com.itsm.investigation.InvestigationEntryType
import com.itsm.investigation.Visibility
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import com.itsm.lifecycle.IncidentState as S

class IncidentAssignmentServiceTest {
    private val log = FakeLog()
    private val audit = RecordingAudit()
    private val engine = AssignmentEngine(listOf(LoadBasedStrategy(), SkillBasedStrategy(), RoundRobinStrategy(), ManualStrategy()), TechnicianRecommender())

    private fun <T : Any> provider(type: Class<T>, bean: T?): ObjectProvider<T> =
        DefaultListableBeanFactory().apply { bean?.let { registerSingleton("bean", it) } }.getBeanProvider(type)

    private fun service(
        store: FakeIncidentStore, directory: UserDirectory? = FakeDirectory(summary(TECH_A), summary(TECH_B)),
        settings: AssignmentSettingsProvider? = FakeSettings()
    ) = IncidentAssignmentService(
        store, log, audit, engine, provider(UserDirectory::class.java, directory),
        provider(AssignmentSettingsProvider::class.java, settings), CLOCK
    )

    private fun busy(vararg ids: Pair<Long, Int>) = ids.flatMap { (tech, n) ->
        (1..n).map { record(id = "BUSY-$tech-$it", state = S.IN_PROGRESS, assignee = tech) }
    }

    // ---- automatic
    @Test fun `load based picks the least loaded technician and records how`() {
        val store = FakeIncidentStore(record(state = S.CLASSIFIED), *busy(TECH_A to 3, TECH_B to 1).toTypedArray())
        val result = service(store).assignAutomatically("INC1", null, admin)
        assertEquals(AssignmentOutcome.ASSIGNED, result.decision.outcome)
        assertEquals(S.ASSIGNED, result.incident.state); assertEquals(TECH_B, result.incident.assigneeId); assertEquals(NOW, result.incident.assignedAt)
        assertEquals(result.incident, store.rows["INC1"])
        val event = audit.events.single()
        assertEquals("INCIDENT_ASSIGNED", event.action)
        assertEquals("CLASSIFIED", event.previousState); assertEquals("ASSIGNED", event.newState)
        assertEquals(mapOf("strategy" to "LOAD_BASED", "technicianId" to TECH_B), event.metadata)
        assertEquals(listOf(TECH_B), store.lockedTechnicians)
        assertTrue(log.entries.isEmpty(), "a first assignment leaves no timeline entry")
    }

    @Test fun `an unregistered configured strategy falls back and the audit says so`() {
        val store = FakeIncidentStore(record(state = S.CLASSIFIED))
        val settings = FakeSettings(AssignmentSettingsView("NOT_A_STRATEGY", 5, emptyMap()))
        service(store, settings = settings).assignAutomatically("INC1", null, admin)
        assertEquals("NOT_A_STRATEGY", audit.events.single().metadata["fellBackFrom"])
    }

    @Test fun `reassigning excludes the current assignee, needs a reason and leaves an internal entry`() {
        val store = FakeIncidentStore(record(state = S.IN_PROGRESS, assignee = TECH_A))
        assertThrows<InvalidInput> { service(store).assignAutomatically("INC1", null, admin) }
        val result = service(store).assignAutomatically("INC1", "Technician A is on leave", admin)
        assertEquals(TECH_B, result.incident.assigneeId); assertEquals(S.ASSIGNED, result.incident.state)
        val entry = log.entries.single()
        assertEquals(InvestigationEntryType.REASSIGNMENT, entry.type); assertEquals(Visibility.INTERNAL, entry.visibility)
        val event = audit.events.single()
        assertEquals("INCIDENT_REASSIGNED", event.action)
        assertEquals(TECH_A, event.metadata["previousTechnicianId"])
        assertTrue(event.metadata.values.none { it.toString().contains("leave") })
    }

    @Test fun `a manual strategy and an empty pool leave the incident alone and say why`() {
        val manual = FakeSettings(AssignmentSettingsView("MANUAL", 5, emptyMap()))
        val store = FakeIncidentStore(record(state = S.CLASSIFIED))
        val first = service(store, settings = manual).assignAutomatically("INC1", null, admin)
        assertEquals(AssignmentOutcome.MANUAL_REQUIRED, first.decision.outcome)
        assertTrue(first.decision.recommendations.isNotEmpty())

        val none = service(store, directory = FakeDirectory(summary(TECH_A, available = false))).assignAutomatically("INC1", null, admin)
        assertEquals(AssignmentOutcome.NO_ELIGIBLE_TECHNICIAN, none.decision.outcome)
        assertEquals(S.CLASSIFIED, store.rows["INC1"]!!.state)
        assertEquals(0, store.saves); assertTrue(audit.events.isEmpty())
    }

    @Test fun `a technician filled up between deciding and locking is refused, not over-assigned`() {
        val store = FakeIncidentStore(record(state = S.CLASSIFIED), *busy(TECH_A to 5).toTypedArray())
        store.staleCounts += mapOf(TECH_A to 0, TECH_B to 99) // what decide() saw; the recheck sees the truth (5 of 5)
        val directory = FakeDirectory(summary(TECH_A), summary(TECH_B, max = 100))
        assertThrows<WorkloadExceeded> { service(store, directory).assignAutomatically("INC1", null, admin) }
        assertEquals(S.CLASSIFIED, store.rows["INC1"]!!.state); assertTrue(audit.events.isEmpty())
    }

    // ---- by hand
    @Test fun `an admin names the technician, even one without the skill`() {
        val store = FakeIncidentStore(record(state = S.CLASSIFIED))
        val updated = service(store, FakeDirectory(summary(TECH_A, skills = emptyMap()))).assignTo("INC1", TECH_A, null, admin)
        assertEquals(TECH_A, updated.assigneeId)
        assertEquals("MANUAL", audit.events.single().metadata["strategy"])
    }

    @Test fun `a named technician is validated`() {
        val store = FakeIncidentStore(record(state = S.CLASSIFIED), *busy(TECH_A to 5).toTypedArray())
        val directory = FakeDirectory(summary(TECH_A), summary(TECH_B, available = false))
        assertThrows<WorkloadExceeded> { service(store, directory).assignTo("INC1", TECH_A, null, admin) }
        assertThrows<TechnicianUnavailable> { service(store, directory).assignTo("INC1", TECH_B, null, admin) }
        assertThrows<ResourceNotFound> { service(store, directory).assignTo("INC1", 12345, null, admin) }
        assertEquals(S.CLASSIFIED, store.rows["INC1"]!!.state)
    }

    @Test fun `reassigning to the same technician is rejected`() {
        val store = FakeIncidentStore(record(state = S.IN_PROGRESS, assignee = TECH_A))
        assertThrows<InvalidInput> { service(store).assignTo("INC1", TECH_A, "again", admin) }
    }

    @Test fun `only an admin assigns, and only from a state where it is legal`() {
        val store = FakeIncidentStore(record(id = "INC1", state = S.CLASSIFIED), record(id = "INC2", state = S.RESOLVED))
        assertThrows<UnauthorizedOperation> { service(store).assignTo("INC1", TECH_A, null, reporter) }
        assertThrows<InvalidStateTransition> { service(store).assignTo("INC2", TECH_B, "x", admin) }
        assertThrows<ResourceNotFound> { service(store).assignTo("INC1", TECH_A, null, stranger) }
    }

    // ---- suggestions
    @Test fun `recommendations leave out the current assignee and need the assign permission`() {
        val store = FakeIncidentStore(record(state = S.IN_PROGRESS, assignee = TECH_A))
        val decision = service(store).recommendations("INC1", admin)
        assertEquals(listOf(TECH_B), decision.recommendations.map { it.technician.userId })
        assertThrows<UnauthorizedOperation> { service(store).recommendations("INC1", techA) }
    }

    // ---- P1 not merged yet
    @Test fun `without P1's providers assignment answers 503 instead of the application failing to start`() {
        val store = FakeIncidentStore(record(state = S.CLASSIFIED))
        for (svc in listOf(service(store, directory = null), service(store, settings = null))) {
            val e = assertThrows<ItsmException> { svc.assignAutomatically("INC1", null, admin) }
            assertEquals(ErrorCode.SERVICE_UNAVAILABLE, e.code)
        }
        assertEquals(0, store.saves)
    }
}
