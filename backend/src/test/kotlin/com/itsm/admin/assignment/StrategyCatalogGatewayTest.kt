package com.itsm.admin.assignment

import com.itsm.common.spi.AssignmentStrategyCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory

/**
 * The ABSENT and FAILED states of the catalog gateway. With P3's real catalog on the classpath the running application can
 * no longer be in the ABSENT state, so it is tested here with an empty bean factory instead of by hiding P3's bean.
 */
class StrategyCatalogGatewayTest {

    private fun gateway(vararg beans: AssignmentStrategyCatalog): StrategyCatalogGateway {
        val factory = StaticListableBeanFactory()
        beans.forEachIndexed { i, b -> factory.addBean("catalog$i", b) }
        return StrategyCatalogGateway(factory.getBeanProvider(AssignmentStrategyCatalog::class.java))
    }

    @Test fun `no catalog bean is ABSENT`() {
        assertSame(StrategyCatalogState.Absent, gateway().inspect())
        assertEquals(StrategyCatalogStatus.ABSENT, gateway().inspect().status)
    }

    @Test fun `a catalog that answers is AVAILABLE with its keys, even when it registers none`() {
        val some = gateway(object : AssignmentStrategyCatalog { override fun registeredKeys() = setOf("LOAD_BASED") }).inspect()
        assertEquals(StrategyCatalogState.Available(setOf("LOAD_BASED")), some)
        val none = gateway(object : AssignmentStrategyCatalog { override fun registeredKeys() = emptySet<String>() }).inspect()
        assertEquals(StrategyCatalogStatus.AVAILABLE, none.status)
    }

    @Test fun `a catalog that throws is FAILED and keeps its cause for the write path`() {
        val boom = IllegalStateException("db down")
        val state = gateway(object : AssignmentStrategyCatalog { override fun registeredKeys(): Set<String> = throw boom }).inspect()
        assertTrue(state is StrategyCatalogState.Failed)
        assertSame(boom, (state as StrategyCatalogState.Failed).cause)
    }
}
