# Shared foundation (P1)

Everything every module depends on lives in `com.itsm.common`. This document is the contract.

## 1. The one rule

> A module may import `com.itsm.common.*` and **nothing from another module's package**.
> Cross-module calls go through an interface in `common/spi`; cross-module notifications go
> through events. Never expose a JPA entity outside its module.

Modules: `access` (P1), `admin` (P1), `audit` (P1), incident intake (P2), lifecycle/assignment (P3),
SLA/priority/escalation/notifications/analytics (P4). Today `auth/` and `user/` still hold the
boilerplate auth code; they move into `access/` during auth hardening.

## 2. What is in `common`

| Package | Contents |
|---|---|
| `model` | `Role` (EMPLOYEE, TECHNICIAN, ADMIN), `Priority` (P1..P4) |
| `security` | `Permission` + `RolePermissions`, `CurrentUser`, `CurrentUserProvider`, `ItsmUserDetails`, JSON 401/403 handlers |
| `error` | `ItsmException` hierarchy, `ErrorCode`, `GlobalExceptionHandler`, `Problems`, `DatabaseErrorClassifier` |
| `api` | `ApiPaths`, `PageQuery`, `PageResponse` |
| `event` | `AuditableEvent`, `AuditActor` |
| `spi` | The provider interfaces (see the integration guide) and their immutable view types |

Team leads are **not** a role. They are technicians whose profile has `isTeamLead` / `reportsTo`.

## 3. Security

* Roles: `EMPLOYEE`, `TECHNICIAN`, `ADMIN`. Each role maps to a fixed set of `Permission`s
  (`RolePermissions`, the single source of truth; add permissions by PR to P1).
* The principal is `ItsmUserDetails(id, email, role)`. Its authorities are `ROLE_<role>` **plus one
  authority per permission**, so both of these work:

```kotlin
@PreAuthorize("hasAuthority('INCIDENT_ASSIGN')")   // preferred: survives role changes
@PreAuthorize("hasRole('TECHNICIAN')")
```

* Method security is on (`@EnableMethodSecurity`). Get the caller with `CurrentUserProvider`:

```kotlin
class IncidentService(private val currentUser: CurrentUserProvider) {
    fun create(...) { val me = currentUser.current() /* 401 if anonymous */ ; ... }
}
```

* `@PreAuthorize` denials are turned into JSON by `GlobalExceptionHandler`:
  anonymous -> 401 `UNAUTHENTICATED`, authenticated -> 403 `FORBIDDEN`.
* Existing flow is unchanged: the JWT is still keyed by email and the unmodified `JwtAuthFilter`
  now simply puts an `ItsmUserDetails` into the security context.
* Legacy rows with `role = 'USER'` (the boilerplate default) are treated as `EMPLOYEE`.

## 4. Errors

Throw a subclass of `ItsmException` from any layer. The response is always RFC 7807
`application/problem+json`:

```json
{ "type": "urn:itsm:error:invalid-state-transition", "title": "Conflict", "status": 409,
  "detail": "Incident cannot move from NEW to CLOSED", "code": "INVALID_STATE_TRANSITION",
  "message": "Incident cannot move from NEW to CLOSED",
  "traceId": "8581f4f8cd764cb0", "timestamp": "2026-11-08T07:01:47Z",
  "errors": [ { "field": "name", "message": "must not be blank" } ] }
```

`errors` appears only for validation failures. `message` duplicates `detail` because the current
frontend client reads `errorData.message`. `traceId` matches the server log line.

| Throw | HTTP | `code` |
|---|---|---|
| `AuthenticationFailed` / `.invalidCredentials()` | 401 | `UNAUTHENTICATED` / `INVALID_CREDENTIALS` |
| `UnauthorizedOperation` | 403 | `FORBIDDEN` |
| `ResourceNotFound(type, id)` / `ServiceNotFound(id)` | 404 | `RESOURCE_NOT_FOUND` / `SERVICE_NOT_FOUND` |
| `InvalidInput(msg, fieldErrors)` | 400 | `INVALID_INPUT` |
| `DuplicateResource` | 409 | `DUPLICATE_RESOURCE` |
| `InvalidStateTransition(entity, from, to)` | 409 | `INVALID_STATE_TRANSITION` |
| `TechnicianUnavailable` / `WorkloadExceeded` | 409 | `TECHNICIAN_UNAVAILABLE` / `WORKLOAD_EXCEEDED` |
| `MissingSlaConfiguration` | 422 | `MISSING_SLA_CONFIG` |
| `ExternalServiceFailure(system, cause)` | 502 | `EXTERNAL_SERVICE_FAILURE` |

**Exception messages are returned to the client verbatim: keep them client-safe** (no SQL, paths,
secrets). The `cause` is logged, never serialized. Unknown exceptions become a generic 500.

### Database errors

Do not catch `DataAccessException` just to translate it; the handler does it, by SQLSTATE, and never
returns SQL text or constraint/table names:

| Persistence failure | Result |
|---|---|
| unique violation (`23505`, `DuplicateKeyException`) | 409 `DUPLICATE_RESOURCE` |
| foreign-key violation (`23503`) | 409 `REFERENCE_CONSTRAINT` |
| not-null / check / data exception (`23502`, `23514`, `22xxx`) | 400 `INVALID_INPUT` |
| other integrity violation | 409 `CONFLICT` |
| optimistic/pessimistic lock failure, deadlock | 409 `CONCURRENT_MODIFICATION` |
| no connection, timeout, shutdown (`08xxx`, `53xxx`, `57P0x`, transient/resource failures) | 503 `SERVICE_UNAVAILABLE` + `Retry-After: 5` |
| anything else (bad SQL grammar, etc.) | 500 `INTERNAL_ERROR` (a bug, not an outage) |

If you want a friendlier message for a specific constraint (e.g. "email already registered"), check
first in the service and throw `DuplicateResource`; the DB mapping is the safety net for races.

## 5. API conventions

`/api/...` base; admin endpoints under `/api/admin`; JSON camelCase; ISO-8601 UTC instants; numeric
ids except incident ids (strings like `INC1024`). Lists use `PageQuery` (`page` from 0, `size` 1..100,
default 20) and return `PageResponse` (`items, page, size, totalItems, totalPages`). Create returns
201 + `Location`, update 200, delete 204.

> **Fixed bug:** `PageQuery` used to reject every input, including the default, because its validation read `size`
> inside `buildList { }`, where a bare `size` is the builder list's own size (always 0), not the requested page size.
> It is fixed in `common/api/PageResponse.kt` and guarded by `PageQueryTest`. Inside a `buildList`/`apply`/`with`
> block, qualify properties of the enclosing class (`this@PageQuery.size`) when the receiver has a member of the same name.

## 6. Auditing

Publish an `AuditableEvent` through `AuditPublisher` **inside your `@Transactional` method**:

```kotlin
@Transactional
fun assign(incidentId: String, techId: Long) {
    // ... change state ...
    audit.publish(AuditableEvent(
        actor = AuditActor.of(currentUser.current()),
        action = "INCIDENT_ASSIGNED", entityType = "INCIDENT", entityId = incidentId,
        incidentId = incidentId, previousState = "CLASSIFIED", newState = "ASSIGNED",
        description = "Assigned to technician $techId",
        metadata = mapOf("technicianId" to techId, "strategy" to "LOAD_BASED")))
}
```

* Calling it without a transaction throws `IllegalTransactionStateException` (`MANDATORY`). For
  scheduled jobs, annotate the job method `@Transactional` and use `AuditActor.system("SLA_MONITOR")`.
* `AuditableEvent` is immutable; it validates its fields on construction and throws
  `IllegalArgumentException` (a programming error -> 500) if you break the rules. Formats: `action`
  and `entityType` UPPER_SNAKE_CASE; `entityId`/`incidentId` `[A-Za-z0-9_-]`; states UPPER_SNAKE_CASE;
  `description` 1..1000 chars.
* **Metadata** value types allowed: `String` (<= 500 chars), `Boolean`, `Int`, `Long`, finite
  `Double`, `BigDecimal`, or a flat `List` of those (<= 20 items). Keys camelCase, <= 20 entries.
  **Not allowed:** null, `Float`/`Short`/`Byte`, `Instant`/`Date` (use an ISO string), nested maps,
  entities. The map is defensively copied and unmodifiable. Never put passwords, tokens or personal
  data (beyond ids) in metadata.
* Audit is not for notifications. If P4 needs to react to a change, that is a separate domain event.

### How persistence works

`SpringAuditPublisher` (propagation `MANDATORY`) hands the event to Spring's event bus. The listener
`AuditEventPersistenceListener` is a `@TransactionalEventListener(phase = BEFORE_COMMIT)`: it inserts one row into
`audit_events` (V10) while the business transaction is still open, immediately before it commits.

```
@Transactional business method
  ├─ change state
  ├─ audit.publish(event)        -> event is queued on the transaction (nothing written yet)
  └─ commit
       ├─ BEFORE_COMMIT: listener INSERTs audit_events row   (same connection, same transaction)
       └─ COMMIT  -> business change and audit row become visible together
```

* **Rollback removes the audit row.** The row was never committed on its own, so when the business transaction
  rolls back (exception or rollback-only) there is nothing to undo. Audit never records an action that did not happen.
* **A failed audit insert fails the business action.** The exception propagates out of the commit and rolls back
  the whole transaction; it is not caught, logged-and-ignored, or retried in another transaction. An unaudited change must
  not become permanent. There is no `REQUIRES_NEW` and no `AFTER_COMMIT`, either of which could keep a row for work that was
  rolled back, or lose one for work that was committed.
* **What is stored:** event id (UUID), `occurred_at`, actor type + stable user id + role (or a system job name),
  `action`, `entity_type`, `entity_id`, `incident_id`, `previous_state`, `new_state`, `description`, `metadata` (JSONB).
  The actor's **email is not stored**: audit rows are permanent, so they identify the actor by id and role only.
  Metadata values read back as JSON (e.g. a `Long` that fits in 32 bits comes back as a number, not a `Long`).
* **Append-only, in three layers:** (1) the PostgreSQL trigger `audit_events_reject_mutation` rejects `UPDATE`, `DELETE`
  and `TRUNCATE` (SQLSTATE 23000) and is the authoritative guard; (2) `AuditEventEntity` is Hibernate `@Immutable` with
  `val` properties; (3) `AuditEventRepository` extends the bare `Repository` and exposes only `save`. There is no audit
  editing or deletion service. The trigger does not stop a database owner who drops it: restrict who owns the schema in
  production. The table has no foreign keys so rows outlive the users and incidents they describe.
* Reading audit data (the admin viewer and the incident audit endpoint) is a later batch; the indexes for it exist.

## 7. Planned auth hardening (not in this branch)

Documented here so teammates can rely on the direction:

* Short-lived access JWT (in memory, sent as `Authorization: Bearer`) + a refresh token in an
  `HttpOnly; Secure; SameSite=Strict; Path=/api/auth` cookie, rotated on every use, stored hashed.
* CSRF applies only to the two cookie-authenticated endpoints (`/api/auth/refresh`, `/api/auth/logout`):
  double-submit CSRF token plus an `Origin` check. Bearer-token endpoints stay stateless with CSRF off.
* Public registration creates EMPLOYEE only; the `role` claim and a `token_version` are added to the JWT
  for revocation; login lockout; the secret moves out of `application.yml`.

## 8. Startup cycle in `SecurityConfig` (resolved in B5)

The boilerplate on `main` never started: `JwtAuthFilter` needed the `userDetailsService` bean, declared *inside*
`SecurityConfig`, whose constructor needed `JwtAuthFilter` (a constructor cycle). This branch first worked around it with
`@Lazy` on the filter parameter, which injects a CGLIB proxy (and logs "Unable to proxy interface-implementing method ...
final" warnings, because `OncePerRequestFilter.doFilter/init` are final). B5 made `JwtAuthFilter` depend on `UserRepository`
directly, which removed the cycle, so `@Lazy` was dropped and the real filter bean is injected.

Guarded by `SecurityWiringContextTest` (no Docker needed) and `ApplicationStartupIntegrationTest`.

## 9. Known pre-existing issues (status after B5)

All three issues originally listed here were fixed by B5: public registration now always creates an `EMPLOYEE` with
normalised, case-insensitively unique emails; `JwtAuthFilter` no longer throws for deleted users or unknown roles (401; a
database outage is 503); and the JWT secret comes from `JWT_SECRET` with no default. See [security.md](security.md).

## 10. Running the build and tests

JDK 17 and Gradle 8.8 are pinned (wrapper checksum is verified). A JDK newer than 21 will not run
Kotlin 1.9.24, so use a JDK 17 toolchain or the same container the Dockerfile uses:

```bash
cd backend
docker run --rm -v "$PWD":/app -w /app -u "$(id -u):$(id -g)" -e GRADLE_USER_HOME=/app/.gradle-home \
  gradle:8.8-jdk17 ./gradlew build --no-daemon
```

Audit persistence tests (all need Docker except the first):

```bash
./gradlew test --tests 'com.itsm.audit.AuditEventPersistenceListenerTest'      # database-free: phase, no swallowing, mapping
./gradlew test --tests 'com.itsm.audit.AuditPersistenceIntegrationTest'        # PostgreSQL 16: commit, rollback, trigger
ITSM_REQUIRE_DOCKER=true ./gradlew test   # what CI does: fails instead of silently skipping the Docker tests
```

`AuditPersistenceIntegrationTest` never deletes rows (the table is append-only); each test writes under its own
unique entity id and only inspects those rows.

Almost all tests are database-free. `ApplicationStartupIntegrationTest` needs Docker (it starts
`postgres:16` with Testcontainers) and is **skipped, not failed**, when Docker is unavailable, so check the
test report if startup coverage matters. When running Gradle itself inside a container, pass the Docker
socket and `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`.
