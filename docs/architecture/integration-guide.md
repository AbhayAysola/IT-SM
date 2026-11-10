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
| `SlaPolicyProvider` | P1 | P4 |
| `AssignmentSettingsProvider` | P1 | P3 |
| `AuditPublisher` | P1 (`SpringAuditPublisher`; events are persisted to `audit_events` in the caller's transaction) | everyone |
| `IncidentAccessPolicy` | **P2 only** | P1 (incident audit endpoint), P3, P4 |
| `TechnicianWorkloadProvider` | **P3 only** | P1 (admin technician views), P4 (SLA risk) |
| `AssignmentStrategyCatalog` | **P3 only** | P1 (validates the configured strategy key) |

Do not write a second implementation of someone else's SPI "for testing" in `src/main`; mock it in tests.

### Status and how to work before P1's implementations land

`AuditPublisher` is fully implemented and persists events (see foundation.md section 6): publish inside your
`@Transactional` method and the row is written with your change, or not at all if it rolls back. `CategoryCatalog` and
`ServiceCatalog` are implemented (see "Category and service catalogs" below). The rest of P1's implementations arrive
in this order: SLA + assignment settings, then users/technician profiles.
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
technician, or admin). Nobody else re-implements these rules. P1's `GET /api/incidents/{id}/audit` calls
`requireView(...)`.

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
| `ServiceCatalog.dependentsOf` | **Direct** dependents only (reverse edges), active **and** inactive; empty set for an unknown id. Walk it yourself for transitive impact; filter with `findById(id)?.active` if you only want live services. |
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
| `POST /api/admin/categories` `{code, name, description?, keywords}` | 201 + `Location` |
| `PUT /api/admin/categories/{id}` `{name, description?, keywords, version}` | 200; `code` cannot change |
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

### Frontend adapter (not yet connected; the admin pages still use mocks)

| Frontend mock | Backend | Adapter needed |
|---|---|---|
| String ids (`c-001`) | Numeric `id` | Use the number; stringify only for display |
| `Page {items, total, page (1-based), pageSize}` | `PageResponse {items, page (0-based), size, totalItems, totalPages}` | Map names; `page - 1` on the way out |
| `status: ACTIVE/INACTIVE`, `setStatus(id, s)` | `active: boolean`, `POST /{id}/activate`/`deactivate` | Map both ways |
| Category has no code | `code` required on create, immutable | The create form needs a code field (or derive one client-side) |
| Category `defaultPriority` | **Not supported** | No shared contract has it (`CategoryView` has none and P4 owns priority). Drop it or agree a contract change with P4 first |
| Category `description` required (10-300) | Optional, max 300 | Frontend may stay stricter |
| `criticality: CRITICAL/HIGH/MEDIUM/LOW` | `businessCriticality: 1..5` | Mapping must be agreed (4 labels vs 5 levels) |
| `dependsOn: string[]`, `dependencyNames`, `dependentNames` (active only) | `dependsOnServiceIds` in requests; `dependsOn[]`/`dependents[]` refs (active and inactive) in responses | Filter `dependents` by `active` for the old display |
| `policyCount` | Not part of this API | Comes with the SLA batch |
| `updatedAt` for change detection | `version` | Send `version` back in PUT; handle 409 `CONCURRENT_MODIFICATION` |
| `ServiceError` codes `VALIDATION`, `DUPLICATE`, `CONFLICT`, `NOT_FOUND` | Problem `code` field | Map `VALIDATION_FAILED`/`INVALID_INPUT`/`MALFORMED_REQUEST` -> VALIDATION, etc. |

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
