package com.itsm.audit

import org.springframework.data.repository.Repository

/**
 * Deliberately NOT a `JpaRepository`/`CrudRepository`: audit rows can be inserted and nothing else. There is no
 * update, delete or deleteAll to call by mistake. Read/query methods arrive with the audit retrieval batch.
 * (The database trigger enforces this regardless of what code does.)
 */
interface AuditEventRepository : Repository<AuditEventEntity, Long> {
    fun save(entity: AuditEventEntity): AuditEventEntity
}
