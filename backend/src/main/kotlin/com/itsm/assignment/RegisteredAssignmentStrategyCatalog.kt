package com.itsm.assignment

import com.itsm.common.spi.AssignmentStrategyCatalog
import org.springframework.stereotype.Component

/**
 * P3's implementation of the SPI P1 uses to validate the strategy key an admin configures. It reports exactly
 * what [AssignmentEngine] can run, so a key that passes validation is never silently replaced by a fallback.
 */
@Component
class RegisteredAssignmentStrategyCatalog(private val engine: AssignmentEngine) : AssignmentStrategyCatalog {
    override fun registeredKeys(): Set<String> = engine.registeredKeys()
}
