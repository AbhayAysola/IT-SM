package com.itsm.common.api

/**
 * API conventions shared by all modules:
 *  - Base path `/api`; admin-only endpoints live under `/api/admin`.
 *  - JSON, camelCase. Instants are ISO-8601 UTC strings. Numeric ids, except incident ids
 *    which are strings (e.g. "INC1024").
 *  - Errors are always RFC 7807 problem+json (see [com.itsm.common.error.Problems]).
 *  - Lists are paged with [PageQuery] (`page` 0-based, `size` 1..100) and returned as [PageResponse].
 *  - Write endpoints return 201 + Location on create, 200 + body on update, 204 on delete.
 */
object ApiPaths {
    const val API = "/api"
    const val AUTH = "$API/auth"
    const val USERS = "$API/users"
    const val ADMIN = "$API/admin"
    const val INCIDENTS = "$API/incidents"
}
