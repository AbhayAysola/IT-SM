# Integration guide for P2, P3 and P4

Read [foundation.md](foundation.md) first (the one rule, errors, security, audit).

## Ownership

| Person | Owns |
|---|---|
| P1 | access, users + technician profiles, categories, IT services, SLA policy config, assignment config, audit infrastructure |
| P2 | incident creation/ingestion, smart categorisation, duplicate detection, conversational reporting |
| P3 | incident state machine, technician assignment, investigation, resolution, feedback |
| P4 | SLA enforcement, priority calculation, escalation, notifications, analytics |

## SPI map

An SPI has exactly **one** implementing module. Everyone else injects the interface.

| Interface (`com.itsm.common.spi`) | Implemented by | Used by |
|---|---|---|
| `UserDirectory` | P1 | P2 (reporter lookup), P3 (technicians), P4 (escalation chain, admins) |
| `CategoryCatalog` | P1 (`JpaCategoryCatalog`, implemented) | P2 (validate, keywords for suggestion), P3, P4 |
| `ServiceCatalog` | P1 (`JpaServiceCatalog`, implemented) | P2 (validate service), P4 (criticality), P2/P4 (dependents for correlation) |
| `SlaPolicyProvider` | P1 (`JpaSlaPolicyProvider`, implemented) | P4 |
| `AssignmentSettingsProvider` | P1 (`JpaAssignmentSettingsProvider`, implemented) | P3 |
| `AuditPublisher` | P1 (`SpringAuditPublisher`; events are persisted to `audit_events` in the caller's transaction; read API: [audit-retrieval.md](audit-retrieval.md)) | everyone |
| `IncidentAccessPolicy` | **P2 only** | P1 (incident audit endpoint), P3, P4 |
| `TechnicianWorkloadProvider` | **P3 only** (no bean exists yet) | P4 (SLA risk); P1 admin technician views are an intention only, no P1 code uses it today |
| `AssignmentStrategyCatalog` | **P3 only** (no bean exists yet) | P1 (validates configured strategy keys; looked up lazily, see "Assignment settings") |

Do not write a second implementation of someone else's SPI "for testing" in `src/main`; mock it in tests.

The full contract matrix (provider status, availability and failure semantics, HTTP surface, per-team handoff) is in
[p1-contract-matrix.md](p1-contract-matrix.md).

### Status and how to work before P1's implementations land

`AuditPublisher` is fully implemented and persists events (see foundation.md section 6): publish inside your
`@Transactional` method and the row is written with your change, or not at all if it rolls back. `CategoryCatalog` and
`ServiceCatalog`, `SlaPolicyProvider` and `AssignmentSettingsProvider` are implemented (see "Category and service catalogs"
and "SLA policies and assignment settings" below). `UserDirectory` is fully implemented (see "Users, authentication and roles" and "Technician profiles").
Until a bean exists, **a `@Service` that constructor-injects it will stop the application from starting**.
So for now:

* Write and unit-test your logic against the interface with a mock (Mockito).
* Do not merge a main-code dependency on an SPI whose provider has not merged, or inject it as
  `ObjectProvider<CategoryCatalog>` and handle the missing case.
* Each SPI's KDoc in `ProviderInterfaces.kt` states the exceptions it throws; catch only the ones you
  can handle (the rest propagate to the global handler).

## P2: incident intake

**Consume:** `CategoryCatalog.requireActive(id)` to validate the reporter's choice;
`CategoryCatalog.listActive()` and `CategoryView.keywords` for the keyword-based category suggestion;
`ServiceCatalog.requireActive(id)`; `CurrentUserProvider` for the reporter (use `CurrentUser.id`, do not
call `UserDirectory` for the caller).

**Implement:** `IncidentAccessPolicy` (you are the sole owner). Contract: throw `ResourceNotFound` if
the incident does not exist, otherwise return whether the user may see it (reporter, assigned
technician, or admin). Nobody else re-implements these rules. P1's `GET /api/incidents/{id}/audit` (implemented in B7)
calls `requireView(...)` through an `ObjectProvider` and answers **503 until your bean exists**; see
[audit-retrieval.md](audit-retrieval.md). Your policy decides only *whether* a caller sees the incident. P1 decides *what*
they see of its audit trail: admins (`AUDIT_VIEW`) get full rows, everyone else a reduced projection without `description`,
`metadata` or actor identity. Registering your bean needs no P1 change. Incident audit is not production-integrated
until you supply and verify it, and your own audit events' metadata/descriptions still need review.

**Hard rules**
* Store `categoryId` and `serviceId` as foreign keys. **Never use a Kotlin enum for category**: categories
  are admin-configurable at runtime.
* `incidentId` is a string such as `INC1024`; the audit table stores it without a foreign key, so your id
  format is yours to choose within `[A-Za-z0-9_-]{1,30}`.
* Duplicate detection should flag, not silently drop. If you reject, throw `DuplicateResource`.
* Authorise endpoints with `@PreAuthorize("hasAuthority('INCIDENT_CREATE')")` etc.

**Audit actions to publish (inside the creating transaction):**
`INCIDENT_CREATED`, `INCIDENT_CATEGORY_SUGGESTED`, `INCIDENT_CLASSIFIED`, `INCIDENT_DUPLICATE_FLAGGED`.
Use `entityType = "INCIDENT"`.

**Migrations:** block `V20-V29`. You may reference tables from V10-V19 (categories, services, users). Reference
categories as `incident_categories (id)` and services as `it_services (id)`; neither is ever deleted, so these foreign
keys never block P1 (and never cascade).

## P3: lifecycle, assignment, investigation, resolution

**Consume:** `AssignmentSettingsProvider.current()` (`strategyFor(categoryId)`, `defaultMaxWorkload`);
`UserDirectory.listAvailableTechnicians(categoryId)` and `findTechnician(id)` for candidates
(`skills`, `maxWorkload`, `experienceYears`, `available`).

**Implement:**
* `TechnicianWorkloadProvider`: you own how workload is counted. `activeIncidentCounts(ids)` should be one query.
* `AssignmentStrategyCatalog.registeredKeys()`: P1 validates the admin-configured key against it.
  Keys are `UPPER_SNAKE_CASE` (e.g. `SKILL_BASED`, `LOAD_BASED`, `ROUND_ROBIN`). **The seeded default
  is `LOAD_BASED`, so register a strategy with exactly that key**, or the default config is invalid.
* Your strategy interface and implementations (Strategy pattern) stay inside your module.

**Throw:** `InvalidStateTransition("Incident", from, to)` for illegal lifecycle moves (states are plain
strings so P1 needs no knowledge of your enum), `TechnicianUnavailable`, `WorkloadExceeded`.
Use `UnauthorizedOperation` if a technician acts on an incident that is not theirs.

**Audit actions:** one per transition with `previousState` and `newState` set, e.g. `INCIDENT_ASSIGNED`
(CLASSIFIED -> ASSIGNED), `INCIDENT_ACKNOWLEDGED`, `INCIDENT_STATUS_CHANGED`, `INCIDENT_RESOLVED`,
`INCIDENT_VERIFIED`, `INCIDENT_CLOSED`, `INCIDENT_FEEDBACK_RECORDED`. Publish **in the same
`@Transactional` method as the state change**, so that the audit row and the state change commit together.

**Migrations:** block `V30-V39`.

## P4: SLA, priority, escalation, notifications, analytics

**Consume:**
* `SlaPolicyProvider.resolve(serviceId, priority)` returns the service-specific policy, else the global
  default. It can throw `ServiceNotFound` and `MissingSlaConfiguration` (422); decide whether you degrade
  (e.g. log + skip SLA tracking) or let it surface. `SlaPolicyView.responseTarget/resolutionTarget`
  are `Duration`s; `warningThresholdPct` is when to warn.
* `ServiceCatalog.findById(id).businessCriticality` (1..5) as an input to your priority score;
  `dependentsOf(serviceId)` for impact.
* `UserDirectory.escalationChain(technicianId)` (nearest team lead first) then `listAdmins()` for the
  Technician -> Team Lead -> System Admin -> IT Manager path. ADMIN covers both admin and IT manager.
* `TechnicianWorkloadProvider` (P3's) for SLA-risk detection.
* `Priority` (P1..P4) lives in `common`. **How** it is computed is yours; document the algorithm.

**Background jobs:** annotate the job method `@Transactional` so audit publishing works, and use
`AuditActor.system("SLA_MONITOR")` (UPPER_SNAKE_CASE). Audit actions such as `SLA_WARNING_RAISED`,
`SLA_BREACHED`, `INCIDENT_ESCALATED`, `INCIDENT_PRIORITY_CHANGED`.

**Notifications:** react to your own/P2/P3 domain events, not to `AuditableEvent` (audit is a record,
not a message bus). Notification channels (in-app, email, ...) should be an interface so a new channel
is a new class.

**Migrations:** block `V40-V49`.

## Category and service catalogs (P1, `V11`/`V12`)

### SPI semantics

The interfaces leave a few points open; this is what the implementations do.

| Method | Behaviour |
|---|---|
| `CategoryCatalog.findById` / `findByCode` | Active **and** inactive categories (history stays displayable). `findByCode` is an exact match on the immutable UPPER_SNAKE_CASE code. `null` if unknown. |
| `CategoryCatalog.listActive` | Active categories ordered by name. `keywords` are normalised (trimmed, lower-case, single spaces) and sorted. |
| `CategoryCatalog.requireActive` | 404 `RESOURCE_NOT_FOUND` if unknown, 400 `INVALID_INPUT` if inactive (interface default). |
| `ServiceCatalog.findById` / `listActive` | As above. `dependsOnServiceIds` are the **direct** dependencies. |
| `ServiceCatalog.dependentsOf` | **Direct** dependents only (reverse edges), active **and** inactive; empty set for an unknown id. Walk it yourself for transitive impact; filter with `findById(id)?.active` if you only want live services. **Current behaviour, pending P2/P4 confirmation** (see "Integration decisions" below). |
| `ServiceCatalog.requireActive` | 404 `SERVICE_NOT_FOUND` if unknown, 400 `INVALID_INPUT` if inactive. |

All views are immutable copies (no JPA entity crosses the boundary). Calls join your transaction if you have one.

**P2** validates the reporter's choice with `requireActive`, matches ticket text against `listActive()` keywords (two
active categories never share a keyword, so a keyword hit is unambiguous), and stores `categoryId`/`serviceId` as foreign
keys. **P4** reads `findById(id).businessCriticality` (1..5) for priority and walks `dependentsOf` for impact.

### Admin API

ADMIN only: every endpoint requires `CATALOG_MANAGE` (`@PreAuthorize`), reads included. No DELETE endpoint exists.

| Method and path | Result |
|---|---|
| `GET /api/admin/categories?page&size&search&active&sort&dir` | 200 `PageResponse<CategoryResponse>`; `search` matches name, code, description, keywords; `sort` = `name` (default), `code`, `createdAt`, `updatedAt` |
| `GET /api/admin/categories/{id}` | 200 `CategoryResponse` |
| `POST /api/admin/categories` `{code, name, description?, keywords}` | 201 + `Location`; `code` is **required** here and nowhere else |
| `PUT /api/admin/categories/{id}` `{name, description?, keywords, version}` | 200; the request has no `code` field: the code is **immutable** after creation |
| `POST /api/admin/categories/{id}/activate` / `deactivate` | 200; repeating one is a no-op |
| `GET /api/admin/services?page&size&search&active&criticality&ownerTeam&sort&dir` | 200 `PageResponse<ServiceResponse>`; `sort` = `name` (default), `businessCriticality`, `createdAt`, `updatedAt` |
| `GET /api/admin/services/{id}` | 200 `ServiceResponse` |
| `POST /api/admin/services` `{name, description?, businessCriticality, ownerTeam?, dependsOnServiceIds?}` | 201 + `Location` |
| `PUT /api/admin/services/{id}` `{name, description?, businessCriticality, ownerTeam?, dependsOnServiceIds, version}` | 200; replaces the dependency list |
| `POST /api/admin/services/{id}/activate` / `deactivate` | 200; repeating one is a no-op |

`CategoryResponse` = `id, code, name, description, keywords, active, version, createdAt, updatedAt`.
`ServiceResponse` = `id, name, description, businessCriticality, ownerTeam, active, dependsOn[], dependents[], version,
createdAt, updatedAt`, where each reference is `{id, name, active}` and both lists are direct edges.

### Rules and errors

| Rule | Error |
|---|---|
| Missing/oversized field, criticality outside 1..5 | 400 `VALIDATION_FAILED` (`errors[]`) |
| Non-numeric criticality or malformed JSON | 400 `MALFORMED_REQUEST` |
| Name 3-60 chars after trimming/collapsing spaces; code `^[A-Z][A-Z0-9_]{0,39}$`; 1-15 keywords of 2-30 chars `[a-z0-9 '&./+-]` after normalisation; bad `sort`/`dir`/paging | 400 `INVALID_INPUT` (`errors[]`) |
| Duplicate keyword within one category (after normalisation) | 400 `INVALID_INPUT` |
| Service depends on itself, lists a dependency twice, or on an unknown service | 400 `INVALID_INPUT` |
| Category name / service name taken (ignoring case and spacing), category code taken | 409 `DUPLICATE_RESOURCE` |
| Keyword used by another **active** category (also on activation) | 409 `CONFLICT` |
| Deactivating the last active category | 409 `CONFLICT` |
| Dependency cycle (message shows the path) | 409 `CONFLICT` |
| Active service depending on an inactive one; deactivating a service an active service depends on; activating a service whose dependency is inactive | 409 `CONFLICT` |
| `version` in a PUT is not the stored one | 409 `CONCURRENT_MODIFICATION` |
| Unknown id | 404 `RESOURCE_NOT_FOUND` (category) / `SERVICE_NOT_FOUND` (service) |

Keyword collisions, case-insensitive names, self-dependencies, duplicate edges and cycles are also enforced by
PostgreSQL. "At least one active category" and the active-dependency rules are application checks serialised by
transaction advisory locks.

### Audit

Each successful mutation publishes in its transaction (actor id + role, `entityId` = the numeric id):
`CATEGORY_CREATED`, `CATEGORY_UPDATED` (`changedFields`, name before/after, `keywordsAdded`/`keywordsRemoved`),
`CATEGORY_ACTIVATED`, `CATEGORY_DEACTIVATED` (entity type `CATEGORY`); `IT_SERVICE_CREATED`, `IT_SERVICE_UPDATED`
(`changedFields` with before/after for name, criticality, owner team), `IT_SERVICE_DEPENDENCIES_CHANGED`
(`addedDependencyIds`/`removedDependencyIds`, first 20 ids plus counts), `IT_SERVICE_ACTIVATED`,
`IT_SERVICE_DEACTIVATED` (entity type `IT_SERVICE`). Activation events carry `previousState`/`newState`. Rejected
requests and no-ops publish nothing.

### Frontend adapter (connected in B8; kept as the record of how the old mocks mapped)

The admin pages now call this API directly: see [admin-frontend-contract.md](admin-frontend-contract.md). The table below is the mapping that was applied.

| Frontend mock | Backend | Adapter needed |
|---|---|---|
| String ids (`c-001`) | Numeric `id` | Use the number; stringify only for display |
| `Page {items, total, page (1-based), pageSize}` | `PageResponse {items, page (0-based), size, totalItems, totalPages}` | Map names; `page - 1` on the way out |
| `status: ACTIVE/INACTIVE`, `setStatus(id, s)` | `active: boolean`, `POST /{id}/activate`/`deactivate` | Map both ways |
| Category has no code | `code` required on create, immutable (`^[A-Z][A-Z0-9_]{0,39}$`, unique) | The create form needs a code field (or derive one client-side); the edit form must not offer to change it |
| Category `defaultPriority` | **Not supported, and will not be added by P1** | No shared contract has it (`CategoryView` has none) and priority belongs to P4. The frontend must drop the field; a backend field would need a P4-agreed contract change |
| Category `description` required (10-300) | Optional, max 300 | Frontend may stay stricter |
| `criticality: CRITICAL/HIGH/MEDIUM/LOW` | `businessCriticality: 1..5` (integer only) | **No mapping is defined and none should be invented client-side.** 4 labels vs 5 levels needs agreement with P4 (see "Integration decisions") |
| `dependsOn: string[]`, `dependencyNames`, `dependentNames` (active only) | `dependsOnServiceIds` in requests; `dependsOn[]`/`dependents[]` refs (active and inactive) in responses | Filter `dependents` by `active` for the old display |
| `policyCount` | Not part of this API | Comes with the SLA batch |
| `updatedAt` for change detection | `version` | Send `version` back in PUT; handle 409 `CONCURRENT_MODIFICATION` |
| `ServiceError` codes `VALIDATION`, `DUPLICATE`, `CONFLICT`, `NOT_FOUND` | Problem `code` field | Map `VALIDATION_FAILED`/`INVALID_INPUT`/`MALFORMED_REQUEST` -> VALIDATION, etc. |

### Integration decisions (recorded after B3)

| # | Decision | Status | Who must act |
|---|---|---|---|
| 1 | **`PageQuery` bug fixed (shared code).** `PageQuery.init` read `size` inside `buildList { }`, where a bare `size` resolves to the builder list's own size (always 0), not the requested page size. Every `PageQuery` was therefore rejected with 400 "size must be between 1 and 100", including the default one. Fixed in `common/api/PageResponse.kt` by qualifying the references (`this@PageQuery.size`); `PageQueryTest` is the regression test. Nothing could have used `PageQuery` successfully before, so no earlier endpoint changed behaviour. | Done, merged with B3 | Everyone: any paged endpoint can now rely on `PageQuery` as documented. Do not revert or rework the fix. |
| 2 | **`ServiceCatalog.dependentsOf(serviceId)` returns direct dependents only, including inactive services** (reverse edges; empty set for an unknown id). It is not transitive and does not filter on `active`. This is how the implementation behaves today and is the contract to code against until it is confirmed. The SPI and implementation will not change without agreement. | **Pending confirmation** | P2 and P4 (the consumers): confirm direct-only and active+inactive suit correlation and impact analysis, or ask P1 for a different behaviour (transitive, or active-only) as an explicit SPI change. |
| 3 | **Categories have no `defaultPriority`.** It exists only in the frontend mock. Priority ownership belongs to P4, `CategoryView` has no such field, and P1 will not add one. | Decided | Frontend: drop it. P4: only if a category-to-priority link is ever wanted, propose the contract change. |
| 4 | **Category `code` is required on create and immutable afterwards.** It is the stable machine identifier behind `CategoryCatalog.findByCode`, UPPER_SNAKE_CASE (`^[A-Z][A-Z0-9_]{0,39}$`), unique, and cannot be edited (the update request has no `code`). Use the numeric `id` for foreign keys and the `code` for stable references in configuration. | Decided | Frontend: add a code input to the category create form and make it read-only on edit. P2: store `categoryId`; use `code` only for lookups. |
| 5 | **Service criticality is numeric 1-5** (`ServiceView.businessCriticality`, a database CHECK, API validation). The frontend's four labels (`CRITICAL/HIGH/MEDIUM/LOW`) have no agreed mapping onto five levels, and none has been invented. | **Pending agreement** | P4 (owner of how criticality feeds priority) with the frontend: define the label-to-number mapping (or move the UI to five levels) before the admin pages are connected. |
| 6 | **Read-only catalog access for EMPLOYEE and TECHNICIAN is missing.** Every `/api/admin/**` endpoint requires an admin permission, but employees and technicians need the active categories and services to report incidents. A separate authenticated, read-only endpoint pair exposing only active categories and services (id, name, and the fields a reporting form needs, no admin fields such as `version`) is required. Not implemented yet. | **Proposed, not built** | Proposed owner: **P1** (it owns the catalogs). Coordinate with **P2**, whose intake form is the consumer, on the paths, fields, and whether categories should expose keywords. Likely authorisation: any authenticated user with `INCIDENT_CREATE`. |

## SLA policies and assignment settings (P1, `V13`/`V14`)

P1 stores and serves **configuration**. P4 owns SLA timers, breach detection, escalation, notifications and priority
calculation. P3 owns assignment strategies, algorithms and workload. Neither is implemented or referenced here.

### SLA policy resolution (`SlaPolicyProvider.resolve(serviceId, priority)`)

1. the **active service-specific** policy for `(serviceId, priority)`;
2. otherwise the **active global** policy for `priority`;
3. otherwise `MissingSlaConfiguration` (HTTP 422 `MISSING_SLA_CONFIG`). **No duration is ever invented.**

Details P4 can rely on:
* An **inactive policy is never returned.** An inactive service-specific policy does not hide an active global one: it falls
  back. Reactivating it makes it win again.
* A global policy is **not guaranteed** for every priority. **Deactivating a global policy can leave a priority with no effective
  policy** for every service that has no active policy of its own: `resolve` then throws the 422 and **no fallback duration is
  invented**. The admin API allows it on purpose (it never blocks a deactivation); P4 must handle the 422.
* An unknown `serviceId` throws `ServiceNotFound` (404). An **inactive service still resolves** against its active
  policies, so historical incidents on a retired service keep working.
* `SlaPolicyView.serviceId == null` tells you the global default supplied the targets. `warningThresholdPct` defaults to 80.
* **Transactions.** `resolve` joins P4's transaction. `MissingSlaConfiguration` and `ServiceNotFound` are answers, not
  failures: they do **not** mark P4's transaction rollback-only, so P4 may catch either, carry on and commit its other work
  (regression test: `SlaProviderCallerTransactionIntegrationTest`). Any other exception, in particular a database or other
  infrastructure failure, propagates unchanged (it is never reported as "no policy") and **does** doom P4's transaction as
  usual, so a swallowed infrastructure error cannot commit as success.
* Cost: two small queries (service existence, then at most two candidate rows); no N+1.
* Cross-priority consistency (P1 never looser than P2, as the frontend mock enforces) is **not** enforced by the backend; see
  open decisions.

### Assignment settings (`AssignmentSettingsProvider.current()`)

`AssignmentSettingsView` is the whole contract: `activeStrategy` (key), `defaultMaxWorkload` and `categoryOverrides`
(categoryId -> key); `strategyFor(categoryId)` returns the override or the active strategy. Nothing else is stored.

**Dependency on P3.** P1 validates every key (active strategy and each override) against `AssignmentStrategyCatalog`, which
P3 owns and which has **no bean yet**. P1 injects it as `ObjectProvider` so the application starts without it:
* reads (`GET`, `current()`) always work and return what is persisted;
* a **write answers 503 `SERVICE_UNAVAILABLE`** while the catalog is unavailable: an unverified key is never accepted;
* once P3's bean exists no P1 change is needed; keys the catalog does not register are rejected with 400.
`GET` reports the catalog in **three states** through `strategyCatalogStatus` (additive field; `availableStrategies` is unchanged):

| `strategyCatalogStatus` | Meaning | `availableStrategies` | A write (`PUT`) |
|---|---|---|---|
| `AVAILABLE` | the catalog answered | its keys, sorted; **`[]` if it registers no strategy** | validated against the keys: unknown key 400 |
| `ABSENT` | no catalog bean exists (P3 not deployed) | `null` | **503** `SERVICE_UNAVAILABLE` with a specific message |
| `FAILED` | a bean exists but threw while being asked | `null` | **generic 500 or 503** (503 for a data-access or transaction failure, 500 otherwise); never a message from the failure |

`availableStrategies` is non-null exactly when the status is `AVAILABLE`. `null` therefore never means "no strategies
exist" (that is `[]`); it means "cannot be verified right now", and `strategyCatalogStatus` says whether that is permanent
until P3 deploys (`ABSENT`) or worth retrying (`FAILED`). Neither state ever permits a write. A `FAILED` catalog never makes
the `GET` itself fail (the stored configuration is served), and a failed write saves nothing and writes no audit row.

**Why a failing catalog cannot poison the read.** `GET` runs in a read-only transaction. A P3 catalog backed by a database
would *join* it, and an exception leaving a joined transactional bean marks the shared transaction rollback-only, so the
`GET` would fail at commit with `UnexpectedRollbackException` even though P1 caught the exception (reproduced in
`ProviderFailureSemanticsIntegrationTest` with a `@Transactional` catalog before the fix). P1 therefore asks the catalog
through `StrategyCatalogGateway`, a separate bean with `Propagation.NOT_SUPPORTED`: the caller's transaction is suspended
for the call, so a transactional catalog runs and rolls back in a transaction of its own. P3 does not need to do anything.
Cost: the caller's connection stays checked out while the catalog runs.

**`LOAD_BASED` is seeded but unverified.** `V14` seeds `LOAD_BASED` / workload 5 (taken from the earlier draft and from the
promise made to P3 above). P3 has not registered it, because P3 has no code on this branch. If P3 registers a different key
set, either P3 registers `LOAD_BASED` or P1 changes the default through a new migration (migrations are never edited).
Until then the first admin write must pick a key P3 registers.

### Admin API

| Method and path | Permission | Result |
|---|---|---|
| `GET /api/admin/sla-policies?page&size&search&active&priority&serviceId&global&sort&dir` | `SLA_CONFIGURE` | 200 `PageResponse<SlaPolicyResponse>`; `search` matches the service name; `sort` = `priority` (default), `responseMinutes`, `resolutionMinutes`, `createdAt`, `updatedAt` |
| `GET /api/admin/sla-policies/{id}` | `SLA_CONFIGURE` | 200 |
| `GET /api/admin/sla-policies/effective?serviceId&priority` | `SLA_CONFIGURE` | 200 `{scope: SERVICE or GLOBAL, policyId, targets}` exactly as P4 would receive, or 404 / 422. A read-only preview of the provider, no P4 logic |
| `POST /api/admin/sla-policies` `{serviceId?, priority, responseMinutes, resolutionMinutes, warningThresholdPct?}` | `SLA_CONFIGURE` | 201 + `Location`; omit `serviceId` for the global policy |
| `PUT /api/admin/sla-policies/{id}` `{responseMinutes, resolutionMinutes, warningThresholdPct?, version}` | `SLA_CONFIGURE` | 200; service and priority cannot change; omitted threshold keeps the current value |
| `POST /api/admin/sla-policies/{id}/activate` / `deactivate` | `SLA_CONFIGURE` | 200; repeating one is a no-op. No DELETE: policies are kept for history. Deactivating a global policy may leave a priority without an effective policy (see resolution) |
| `GET /api/admin/assignment-settings` | `ASSIGNMENT_CONFIGURE` | 200 `{activeStrategy, defaultMaxWorkload, categoryOverrides[{categoryId, categoryName, categoryActive, strategy}], availableStrategies (string[] or null), strategyCatalogStatus (AVAILABLE, ABSENT or FAILED), version, updatedAt, updatedByUserId}` |
| `PUT /api/admin/assignment-settings` `{activeStrategy, defaultMaxWorkload, categoryOverrides[{categoryId, strategy}], version}` | `ASSIGNMENT_CONFIGURE` | 200; replaces the whole configuration (overrides not listed are removed) |

`SlaPolicyResponse` = `id, service{id,name,active} or null, priority, responseMinutes, resolutionMinutes,
warningThresholdPct, active, version, createdAt, updatedAt`. Priorities are the shared codes `P1`..`P4`.

### Rules and errors

| Rule | Error |
|---|---|
| Missing field, malformed JSON | 400 `VALIDATION_FAILED` / `MALFORMED_REQUEST` |
| Targets: response >= 1, resolution > response, resolution <= 43200 (30 days); threshold 1-99; unknown priority | 400 `INVALID_INPUT` (`errors[]`) |
| Workload 1-100; strategy key `^[A-Z][A-Z0-9_]{1,49}$`; duplicate category in overrides; incomplete or unknown-category override; max 100 overrides | 400 `INVALID_INPUT` |
| Strategy key not registered in P3's catalog | 400 `INVALID_INPUT` (message lists the registered keys) |
| P3's catalog unavailable on any write | 503 `SERVICE_UNAVAILABLE` |
| New policy for an unknown service / an inactive service | 404 `SERVICE_NOT_FOUND` / 400 `INVALID_INPUT` |
| Another active policy in the same scope (same service and priority, or global and priority), also on activation | 409 `DUPLICATE_RESOURCE` |
| Stale `version` | 409 `CONCURRENT_MODIFICATION` |
| Unknown policy id | 404 `RESOURCE_NOT_FOUND` |

The partial unique indexes of `V13` enforce the scope rule in PostgreSQL as well (also under races).

### Audit

In the mutation's transaction, with the actor's id and role: `SLA_POLICY_CREATED`, `SLA_POLICY_UPDATED` (changed
fields with before/after targets and threshold), `SLA_POLICY_ACTIVATED`, `SLA_POLICY_DEACTIVATED` (entity type
`SLA_POLICY`, `entityId` = policy id; activation events carry `previousState`/`newState`); `ASSIGNMENT_SETTINGS_UPDATED`
(entity type `ASSIGNMENT_SETTINGS`, `entityId` `1`; changed fields, strategy and workload before/after, overrides
added/removed/changed as category ids plus counts). Rejected requests, 503s and no-ops publish nothing.

### Frontend adapter (connected in B8; kept as the record of how the old mocks mapped)

The admin pages now call this API directly: see [admin-frontend-contract.md](admin-frontend-contract.md). The table below is the mapping that was applied.

| Frontend mock | Backend | Adapter needed |
|---|---|---|
| SLA ids are strings (`p-...`) | Numeric `id` | Use the number |
| `serviceId` always required | `service` is nullable: **null = global policy** | The UI can add a "global default" scope or keep only service policies |
| `serviceName`, `serviceStatus` | `service {id, name, active}` | Map |
| `status: ACTIVE/INACTIVE`, `setStatus` | `active`, `activate`/`deactivate` | Map both ways |
| `remove(id)` (delete a policy) | **No delete**; deactivate instead | Replace the delete action; the row stays in history |
| `update` takes only the two targets | Same, plus optional `warningThresholdPct`, and `version` is required | Send back `version`; handle 409 |
| No warning threshold | `warningThresholdPct` (SPI field, default 80) | Show it or ignore it (default applies) |
| Cross-priority rule (P1 not looser than P2) in the UI | **Not enforced by the backend** | Keep in the UI; decision needed (below) |
| `coverageGaps` (services with no policy) | Not part of this API | Derive from list data, or ask for an endpoint |
| `Page {total, page 1-based, pageSize}` | `PageResponse {totalItems, page 0-based, size, totalPages}` | Map names; `page - 1` |
| Strategies `MANUAL`, `ROUND_ROBIN`, `SKILL_BASED`; default `SKILL_BASED` | Keys come from **P3's catalog**; the seeded default is `LOAD_BASED` | The UI must offer `availableStrategies` instead of a fixed list; the frontend names are placeholders, not a contract |
| `fallbackStrategy`, `recommendationCount`, `manual.notifyTeamLeads`, `roundRobin{pool,teams,skipAtCapacity}`, `skillBased{minSkillMatchPct,minSkillLevel,tieBreaker}` | **Not supported:** the SPI carries only strategy, default workload and per-category overrides | Strategy-specific options belong to P3; send nothing for them. Decision needed |
| `unassignedEscalationMinutes` | **Not supported** | Escalation timing is P4's domain. Decision needed |
| Settings can be "not configured yet" (`null`) | Always configured (seeded singleton) | Drop the empty state |
| `updatedBy` is a display name | `updatedByUserId` (id only, no personal data) | Resolve a name through the user module later |
| Assignment has no default workload or category overrides | `defaultMaxWorkload` (1-100) and `categoryOverrides` | New form controls |

### Open decisions for P3 / P4 / the frontend

1. **P3:** register `LOAD_BASED` (the seeded default), or agree a different default key; provide the `AssignmentStrategyCatalog`
   bean. Until then assignment writes return 503.
2. **P3:** the frontend's strategy options (fallback, round-robin pool, skill thresholds, recommendations) are not in the SPI.
   If P3 wants them stored by P1, that is a coordinated SPI change, not something P1 adds on its own.
3. **P4:** `unassignedEscalationMinutes` (frontend) has no home in the SLA contract; decide whether it belongs to P4's configuration.
4. **P4:** should the backend enforce the cross-priority rule (more urgent targets never looser) per scope? It is a UI-only rule today.
5. **P4:** confirm that resolving an inactive service against its active policies, and 422 when nothing applies, suit SLA tracking.
6. **P1 limit to confirm:** `defaultMaxWorkload` is capped at 100 (the SPI only says positive); say if another limit is wanted.

## Users, authentication and roles (P1, `V15`)

Full detail: [security.md](security.md). What other modules need:

* **Identity**: inject `CurrentUserProvider` (`current()` -> `CurrentUser(id, email, role)`). Never read the role from a token
  or request. The role is always the stored one.
* **`UserDirectory`** (`JpaUserDirectory`): `findById`/`requireById`, `listAdmins` (active ADMINs only) return immutable
  `UserSummary(id, email, name, role, active)`. Technician methods are specified in "Technician profiles" below. `findById`
  still returns inactive users (check `active`).
* **Authentication (frontend and other clients)**: the web app signs in with `X-ITSM-Client: web`, receives an HttpOnly `itsm_session`
  cookie and `{user}` (no token), sends `credentials: 'include'` plus `X-ITSM-Client: web` on every call, and asks `GET /api/users/me`
  after a reload. Non-browser clients log in without that header and use `Authorization: Bearer <token>`. Unsafe cookie requests also need an
  `Origin` from `CORS_ALLOWED_ORIGINS`. Logout (`POST /api/auth/logout`) revokes every session of the user. See `security.md`, "Browser
  sessions". Modules P2-P4 never touch this: they receive an authenticated `CurrentUser` either way.
* **Authorization**: `@PreAuthorize("hasAuthority('<PERMISSION>')")`; permissions are unchanged. Roles: `EMPLOYEE`,
  `TECHNICIAN`, `ADMIN`, nothing else.
* **Tests**: `AdminApiTestSupport.caller(role)` inserts a user and mints a valid token (`generateToken(userId, tokenVersion)`).
  Test emails must be lower-case (DB CHECK). The test JVM gets `JWT_SECRET` from `build.gradle.kts`.

## Technician profiles (P1, `V16`)

Handoff for P3 (and anyone calling `UserDirectory`). **Contract status: pending P3 confirmation** (P3's consumer does not exist yet; no shared signature was changed). P1 stores and serves profile DATA; P3 owns workload, recommendation and
assignment. Admin API: `/api/admin/technicians` (see [security.md](security.md) for the permission; `USER_MANAGE`, ADMIN only).

**Identifiers.** The profile id IS the canonical user id (`TechnicianSummary.userId`); one profile per user. Skills are keyed by
`CategoryView.id` (the SPI's `categoryId`), never by name or code (code is the stable external key, id the SPI key).

**What "usable" means.** Every technician method returns only technicians whose profile is ACTIVE, whose user account is ACTIVE
and whose role is TECHNICIAN. Inactive profiles/users are excluded, not flagged: `TechnicianSummary` has no `active` field, so
P3 cannot tell "inactive" from "unknown". Use `findById` for the account itself.

| Method | Semantics |
|---|---|
| `findTechnician(userId)` | the usable technician, else `null` (no profile, inactive profile, inactive user, or not a TECHNICIAN) |
| `listAvailableTechnicians(categoryId?)` | usable technicians whose administrator-set `available` flag is true; with `categoryId`, only those with a skill for that ACTIVE category. Ordered by name (case-insensitive) then user id. **Workload is not considered** |
| `escalationChain(technicianId)` | the reporting line above the technician, nearest first, excluding the technician; managers whose profile or account is inactive are skipped and the walk continues. Empty if no usable reporting line. No other hierarchy or fallback is invented |

**Field meaning (do not over-read).**
* `available` = an administrator's flag (on leave, off shift). It is **not** workload, not capacity and not "assignable now".
  An active profile is not automatically available, and an available one may be at capacity: P3 must combine this with
  its own workload calculation and `maxWorkload`.
* `maxWorkload`: configured CEILING of concurrent incidents, never the current count. `experienceYears`: stored input (0..60).
  P1 never computes or enforces workload; P3 compares its own current count against `maxWorkload`.
* Inactive users and inactive profiles never appear in any technician result (only `findById` still returns an inactive account).
* `skills`: `categoryId -> proficiency 1..5`, only for categories that are active now. Inactive-category skills are kept
  in storage and omitted here.
* `isTeamLead` is an attribute (not a role). `reportsToUserId` is the stored line; a manager is always an ACTIVE team lead.
* Query cost is constant (page rows + skills; one recursive query for the chain): safe to call per incident.

**Contract points for P3 to confirm (nothing was changed in shared interfaces).**
1. `listAvailableTechnicians` filters by an admin flag only. If P3 wants a workload-aware list it should call this and filter
   with `TechnicianWorkloadProvider`; P1 will not add workload to this SPI.
2. The SPI cannot express "profile exists but inactive". If P3 needs that (e.g. an incident assigned to a technician who was
   deactivated) the smallest compatible change is a default method such as `findTechnicianIncludingInactive` or an `active`
   field with a default; propose it before using it.
3. The retired frontend mocks used `skillLevel`, `availability` (AVAILABLE/BUSY/OFF_SHIFT/ON_LEAVE), `team`, `shift`, `currentWorkload`.
   None exist in the SPI or V16 and none were added (no use case): proficiency is per category, availability is one boolean,
   `currentWorkload` comes from P3, `team`/`shift` are deferred.

**Errors.** A user with an ACTIVE profile cannot change role: `409 CONFLICT` "This user has an active technician profile; deactivate the profile before changing the role." (database trigger SQLSTATE `IT001`). Other profile invariants raise `IT002` -> 409 with generic wording. Nothing else is mapped to 409 by these codes, and no SQL, table or trigger text reaches the client.

**Admin rules in short.** A profile can only be created for an existing, active TECHNICIAN user (never changes the role; 409
otherwise); duplicates are 409; updates carry `version` (stale = 409); a user with an ACTIVE profile cannot lose the TECHNICIAN
role (deactivate the profile first); a team lead with reports cannot be un-flagged or deactivated; reporting lines forbid
self, cycles and inactive-profile, inactive-account or non-lead managers. Deactivating a manager's USER account does not
remove existing reports (user administration is separate): the escalation chain simply skips an inactive manager. Deliberately deferred: bulk operations, deleting profiles, team/shift fields.
Audit actions (entity `TECHNICIAN`, entity id = user id): `TECHNICIAN_PROFILE_CREATED|UPDATED|ACTIVATED|DEACTIVATED`,
`TECHNICIAN_REPORTING_CHANGED`, `TECHNICIAN_SKILLS_CHANGED`; metadata has ids and numbers only, never names or emails.

## Recipes

Secured, paged, audited endpoint:

```kotlin
@RestController @RequestMapping("/api/admin/things")
class ThingController(private val service: ThingService) {
    @GetMapping @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        service.list(PageQuery(page, size))            // PageQuery validates; returns PageResponse<ThingDto>
}
```

Testing an endpoint's error contract without a database: see `GlobalExceptionHandlerTest` (standalone
MockMvc) and `MethodSecurityTest` (full filter chain + `@PreAuthorize`) for copy-paste setups.
Testing code that publishes audit events: mock `AuditPublisher`, or see `SpringAuditPublisherTest`.

## Change process

Anything in `common/` changes through a PR reviewed by P1. SPI signatures are a contract: add methods
with defaults rather than changing existing ones. `SecurityConfig` and `GlobalExceptionHandler` are
edited only by P1; ask for what you need.
