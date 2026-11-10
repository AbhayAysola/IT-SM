package com.itsm.admin.assignment

import com.itsm.common.spi.AssignmentSettingsProvider
import com.itsm.common.spi.AssignmentSettingsView
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.Collections

/**
 * P1's implementation of the shared [AssignmentSettingsProvider] SPI, consumed by P3. Returns an immutable
 * [AssignmentSettingsView] copy, never the entity. The configuration row always exists (V14 seeds it and a trigger
 * forbids deleting it). The returned strategy keys are what an administrator configured and P3's catalog accepted at
 * write time; this provider does not re-verify them.
 */
@Component
@Transactional(readOnly = true)
class JpaAssignmentSettingsProvider(private val settings: AssignmentSettingsRepository) : AssignmentSettingsProvider {

    override fun current(): AssignmentSettingsView {
        val s = settings.findById(AssignmentSettingsEntity.SINGLETON_ID)
            .orElseThrow { IllegalStateException("The assignment settings row is missing (migration V14 seeds it)") }
        return AssignmentSettingsView(
            activeStrategy = s.activeStrategy,
            defaultMaxWorkload = s.defaultMaxWorkload,
            categoryOverrides = Collections.unmodifiableMap(LinkedHashMap(s.categoryOverrides))
        )
    }
}
