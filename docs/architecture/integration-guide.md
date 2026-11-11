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
| `CategoryCatalog` | P1 | P2 (validate, keywords for suggestion), P3, P4 |
| `ServiceCatalog` | P1 | P2 (validate service), P4 (criticality), P2/P4 (dependents for correlation) |
| `SlaPolicyProvider` | P1 | P4 |
| `AssignmentSettingsProvider` | P1 | P3 |
| `AuditPublisher` | P1 (`SpringAuditPublisher` exists, **publishes only; events are not persisted yet**) | everyone |
| `IncidentAccessPolicy` | **P2 only** | P1 (incident audit endpoint), P3, P4 |
| `TechnicianWorkloadProvider` | **P3 only** | P1 (admin technician views), P4 (SLA risk) |
| `AssignmentStrategyCatalog` | **P3 only** | P1 (validates the configured strategy key) |

Do not write a second implementation of someone else's SPI "for testing" in `src/main`; mock it in tests.

### Status and how to work before P1's implementations land

Only `AuditPublisher` has an implementation today, and it does **not** persist events yet
(a WARN is logged at startup). Your audit calls are correct now and will be stored, with no code change,
once P1's audit branch adds the database listener. Do not rely on reading audit rows back until then. P1's implementations arrive in this order:
audit persistence, then categories/services, then SLA + assignment settings, then users/technician profiles.
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

**Migrations:** block `V20-V29`. You may reference tables from V10-V19 (categories, services, users).

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
`@Transactional` method as the state change**, so that once audit persistence lands the two commit together.

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
