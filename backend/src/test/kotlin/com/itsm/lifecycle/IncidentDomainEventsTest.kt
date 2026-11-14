package com.itsm.lifecycle

import com.itsm.assignment.AssignmentEngine
import com.itsm.assignment.AssignmentOutcome
import com.itsm.assignment.LoadBasedStrategy
import com.itsm.assignment.ManualStrategy
import com.itsm.assignment.RoundRobinStrategy
import com.itsm.assignment.SkillBasedStrategy
import com.itsm.assignment.TechnicianRecommender
import com.itsm.common.event.IncidentAssigned
import com.itsm.common.event.IncidentAssignmentFailed
import com.itsm.common.event.IncidentStateChanged
import com.itsm.common.spi.AssignmentSettingsProvider
import com.itsm.common.spi.UserDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.context.ApplicationEventPublisher
import com.itsm.lifecycle.IncidentState as S
import com.itsm.lifecycle.IncidentTransition as T

/** P3's services publish the domain events of docs/architecture/p4.md section 6, with ids only, once per change. */
class IncidentDomainEventsTest {
    private val published = mutableListOf<Any>()
    private val events = ApplicationEventPublisher { published += it }
    private val engine = AssignmentEngine(listOf(LoadBasedStrategy(), SkillBasedStrategy(), RoundRobinStrategy(), ManualStrategy()), TechnicianRecommender())

    private fun <T : Any> provider(type: Class<T>, bean: T?): ObjectProvider<T> =
        DefaultListableBeanFactory().apply { bean?.let { registerSingleton("bean", it) } }.getBeanProvider(type)

    private fun lifecycle(store: FakeIncidentStore) = IncidentLifecycleService(store, FakeLog(), FakeFeedback(), RecordingAudit(), CLOCK, events)

    private fun assignment(store: FakeIncidentStore, directory: UserDirectory = FakeDirectory(summary(TECH_A), summary(TECH_B))) =
        IncidentAssignmentService(
            store, FakeLog(), RecordingAudit(), engine, provider(UserDirectory::class.java, directory),
            provider(AssignmentSettingsProvider::class.java, FakeSettings()), CLOCK, events
        )

    @Test fun `every transition publishes one state change with the move and the actor`() {
        lifecycle(FakeIncidentStore(record(state = S.ASSIGNED))).transition("INC1", T.ACKNOWLEDGE, null, techA)
        assertEquals(listOf(IncidentStateChanged("INC1", "ASSIGNED", "ACKNOWLEDGED", "ACKNOWLEDGE", techA.id, NOW)), published)
    }

    @Test fun `a refused transition publishes nothing`() {
        assertThrows<Exception> { lifecycle(FakeIncidentStore(record(state = S.ASSIGNED))).transition("INC1", T.RESOLVE, "x", techA) }
        assertTrue(published.isEmpty())
    }

    @Test fun `an assignment publishes the state change and who it went to`() {
        val result = assignment(FakeIncidentStore(record(state = S.CLASSIFIED))).assignAutomatically("INC1", null, admin)
        val assignee = result.incident.assigneeId!!
        assertEquals(
            listOf(
                IncidentStateChanged("INC1", "CLASSIFIED", "ASSIGNED", "ASSIGN", admin.id, NOW),
                IncidentAssigned("INC1", assignee, null, admin.id, NOW)
            ),
            published
        )
    }

    @Test fun `a reassignment names the previous assignee`() {
        assignment(FakeIncidentStore(record(state = S.IN_PROGRESS, assignee = TECH_A))).assignTo("INC1", TECH_B, "On leave", admin)
        assertEquals(IncidentStateChanged("INC1", "IN_PROGRESS", "ASSIGNED", "REASSIGN", admin.id, NOW), published[0])
        assertEquals(IncidentAssigned("INC1", TECH_B, TECH_A, admin.id, NOW), published[1])
    }

    @Test fun `nobody eligible publishes an assignment failure and changes nothing`() {
        val store = FakeIncidentStore(record(state = S.CLASSIFIED))
        val result = assignment(store, directory = FakeDirectory()).assignAutomatically("INC1", null, admin)
        assertEquals(AssignmentOutcome.NO_ELIGIBLE_TECHNICIAN, result.decision.outcome)
        assertEquals(listOf(IncidentAssignmentFailed("INC1", "NO_ELIGIBLE_TECHNICIAN", NOW)), published)
        assertEquals(S.CLASSIFIED, store.rows["INC1"]!!.state)
    }
}
