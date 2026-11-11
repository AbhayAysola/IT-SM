# P1 integration contract matrix (B9)

Single place that says, for every shared SPI and every P1 HTTP surface, **what exists, who owns it, what happens when it is
absent, empty or failing, and how sure we are**. It was produced by inspecting the code at the B9 base (B1 to B7) and is
backed by tests named in each row. Detail per area stays in [integration-guide.md](integration-guide.md),
[security.md](security.md) and [audit-retrieval.md](audit-retrieval.md); this page does not repeat it, it makes the gaps visible.

**Labels used on this page**

| Label | Meaning |
|---|---|
| **Confirmed** | Implemented in this repository and covered by an integration test that runs in CI |
| **Pending team confirmation** | Implemented by P1 against an assumption the consumer has not yet confirmed (no consumer code exists) |
| **Deferred** | Deliberately not built; the reason is recorded |
| **Test-only** | Exists only under `src/test`, never in `src/main` |
| **Not implemented** | The provider's team has not delivered it; nothing in this repository stands in for it |

No P2, P3 or P4 code exists in this repository or on any remote branch at the B9 base. Every statement below about those
teams' behaviour is therefore a contract, not an observation. This page was written from the backend only. B8's
[admin-frontend-contract.md](admin-frontend-contract.md) is the frontend's side of the same surface and has since been merged
alongside it; where they differ on a point of fact, the backend code and its tests win.

## 1. SPI matrix (`com.itsm.common.spi`)

| SPI | Provider (owner) | Provider status | Intended consumers | Consumed inside P1 today | Sync / transactional | If the provider bean is absent |
|---|---|---|---|---|---|---|
| `UserDirectory` | P1 `JpaUserDirectory` | **Confirmed** (identity); technician methods **pending P3 confirmation** | P2, P3, P4 | `TechnicianAdminService` | Synchronous; `@Transactional(readOnly)`, joins the caller's transaction | n/a, P1 always provides it |
| `CategoryCatalog` | P1 `JpaCategoryCatalog` | **Confirmed** | P2, P3, P4 | `TechnicianAdminService` | Synchronous; read-only, joins caller's transaction | n/a |
| `ServiceCatalog` | P1 `JpaServiceCatalog` | **Confirmed** | P2, P4 | `SlaPolicyAdminService` | Synchronous; read-only, joins caller's transaction | n/a |
| `SlaPolicyProvider` | P1 `JpaSlaPolicyProvider` | **Confirmed** | P4 | `SlaPolicyAdminService` (the "effective policy" preview) | Synchronous; read-only, joins caller's transaction | n/a |
| `AssignmentSettingsProvider` | P1 `JpaAssignmentSettingsProvider` | **Confirmed** | P3 | none (P1 reads its own repository) | Synchronous; read-only | n/a |
| `AuditPublisher` | P1 `SpringAuditPublisher` | **Confirmed** | everyone | every P1 write path | `Propagation.MANDATORY`: throws if there is no surrounding transaction; the row is written `BEFORE_COMMIT` in that same transaction | n/a |
| `IncidentAccessPolicy` | **P2 only** | **Not implemented**; **test-only** fakes exist | P1 incident audit endpoint; P3; P4 | `AuditQueryService.listForIncident` through `ObjectProvider` | Synchronous; runs in the request's read-only transaction | Incident audit trail answers **503 `SERVICE_UNAVAILABLE`** for everyone, admins included |
| `TechnicianWorkloadProvider` | **P3 only** | **Not implemented** | P3, P4 (SLA risk) | **none**: no P1 code injects it. `integration-guide.md` lists "P1 admin technician views" as a consumer; that is an intention, not code | n/a | n/a: nothing in P1 depends on it, so nothing degrades |
| `AssignmentStrategyCatalog` | **P3 only** | **Not implemented**; **test-only** fakes exist | P1 settings validation | `AssignmentSettingsAdminService` through `ObjectProvider` | Synchronous, called inside the settings request | Reads work with `strategyCatalogStatus: ABSENT` and `availableStrategies: null`; **writes answer 503** |

A row marked **Not implemented** is *not integrated*, even though the interface compiles. Do not infer integration from the
interface's existence.

### Signatures, nullability and errors

Unchanged from `ProviderInterfaces.kt` and `SpiModels.kt` (no SPI signature was touched in B9). Points worth stating once:

* **Never null in a view:** every field except those marked `?`. `CategoryView.description`, `ServiceView.description`,
  `ServiceView.ownerTeam`, `SlaPolicyView.serviceId` (null means the global default), `TechnicianSummary.reportsToUserId`.
* **Collections are read-only snapshots.** Nothing returned is a JPA entity or a live collection.
* **"Not found" is expressed three ways, by design:** `findById`/`findByCode`/`findTechnician` return `null`;
  `requireById`/`requireActive` throw `ResourceNotFound` (404) or `ServiceNotFound` (404); `dependentsOf` and the list
  methods return an empty collection. An *unknown* id is never silently an empty list except for `dependentsOf`.
* **Inactive is not absent.** `findById` returns inactive categories, services and users (check `active`); technician
  methods exclude anything not *usable*; `SlaPolicyProvider` never returns an inactive policy but still resolves for an
  inactive service.
* **Errors** thrown by providers: `ResourceNotFound`/`ServiceNotFound` -> 404; `InvalidInput` (deactivated category or
  service passed to `requireActive`) -> 400; `MissingSlaConfiguration` -> 422 `MISSING_SLA_CONFIG`.

## 2. Provider availability matrix (what P1 does when another team's provider is not behaving)

| Situation | `AssignmentStrategyCatalog` (P3) | `IncidentAccessPolicy` (P2) | Test |
|---|---|---|---|
| Bean absent | Startup succeeds. `GET` 200, `strategyCatalogStatus: ABSENT`, `availableStrategies: null`. `PUT` 503 (specific message) | Startup succeeds. Incident trail 503 (never an empty 200, never an own access rule) | `AssignmentSettingsWithoutCatalogIntegrationTest`, `IncidentAuditProviderUnavailableIntegrationTest` |
| Present, answers | `AVAILABLE` with its sorted keys. `PUT` validates every key; unknown key 400 | Policy decides; allowed -> 200 (an empty page is a valid 200), refused -> 403, unknown incident -> 404 | `AssignmentSettingsIntegrationTest`, `IncidentAuditIntegrationTest`, `ProviderFailureSemanticsIntegrationTest` |
| Present but **empty** | `AVAILABLE`, `availableStrategies: []` (distinct from `null`); every `PUT` 400 | n/a (the policy returns a boolean) | `ProviderFailureSemanticsIntegrationTest` |
| Throws a **domain** exception | n/a (the SPI declares none) | `ResourceNotFound` -> exactly 404 `RESOURCE_NOT_FOUND`; `UnauthorizedOperation` -> exactly 403 `FORBIDDEN` | `ProviderFailureSemanticsIntegrationTest` |
| Throws an **infrastructure** exception | `GET`: **200, `FAILED`, `availableStrategies: null`**, stored configuration served. `PUT`: generic **500** (`IllegalStateException`) or **503** (`DataAccessException`, `TransactionException`), nothing saved, no audit row, nothing leaked | Same mapping: **500 `INTERNAL_ERROR`** or **503 `SERVICE_UNAVAILABLE`**, nothing leaked | `ProviderFailureSemanticsIntegrationTest` |
| Throws inside a **joined transaction** (a database-backed provider) | `GET` still 200 `FAILED`, and the next request is unaffected: the catalog is called with `NOT_SUPPORTED` through `StrategyCatalogGateway` | Not applicable: P1 does not catch the policy's exceptions, so the request fails by design | `ProviderFailureSemanticsIntegrationTest` (`@Transactional` test catalog) |

Rules this encodes: a missing or failing provider is **never** reported as a valid empty result, and no fallback ever
invents strategies, technicians, workload or policy values. There is no fake provider in `src/main`.

## 3. P1 HTTP surface (the frontend side is in [admin-frontend-contract.md](admin-frontend-contract.md))

Base conventions, all verified by tests:

* **Auth:** `Authorization: Bearer <jwt>`. Public: `POST /api/auth/login`, `POST /api/auth/register`, `GET /actuator/health/**`,
  `GET /actuator/info`. Everything else needs a valid token.
* **Pagination:** `page` (0-based, default 0) and `size` (default 20, **max 100**). Out of range -> 400 `INVALID_INPUT` with an
  `errors[]` entry. Response: `{ items, page, size, totalItems, totalPages }`.
* **Sorting:** `sort` must be one of a per-endpoint whitelist and `dir` is `asc` or `desc`; anything else is 400, never ignored.
  Every sort ends with an `id` tie-break, so paging is stable. Audit events default to newest first.
* **Time:** ISO-8601 instants in UTC with `Z`, e.g. `2026-11-10T11:30:11.546998Z` (up to microseconds). Filters accept any ISO offset date-time.
* **Optimistic locking:** every mutable resource returns `version`. Updates (`PUT`) must send the one last read; stale -> 409
  `CONCURRENT_MODIFICATION`. Activate/deactivate differ by resource: on **users and technicians** they require the
  last-read `version` too; on **categories, IT services and SLA policies** they take **no version**: they are idempotent
  target-state operations (repeating one answers 200 and changes nothing), serialised by an advisory lock (categories,
  services) or the V13 unique indexes (SLA policies). A toggle acts on the state stored *now*, not on the admin's snapshot.
* **Soft deactivation:** nothing is deleted. `POST /{id}/deactivate` and `/activate` flip `active`; the row stays, references
  stay valid.
* **Errors:** RFC 7807 `application/problem+json` with `type, title, status, detail, instance, code, message, traceId, timestamp`
  and, for validation, `errors: [{ field, message }]`. Infrastructure failures are generic (no SQL, hostnames or class names).

| Area | Routes | Authority (URL layer **and** `@PreAuthorize`) |
|---|---|---|
| Users | `/api/admin/users` list, get, create, update, `PUT /{id}/role`, activate, deactivate, `reset-password`, `revoke-sessions` | `USER_MANAGE` |
| Technicians | `/api/admin/technicians` list, get, create, update, `PUT /{userId}/skills`, activate, deactivate | `USER_MANAGE` |
| Categories | `/api/admin/categories` list, get, create, update, activate, deactivate | `CATALOG_MANAGE` |
| IT services | `/api/admin/services` list, get, create, update, activate, deactivate | `CATALOG_MANAGE` |
| SLA policies | `/api/admin/sla-policies` list, `effective`, get, create, update, activate, deactivate | `SLA_CONFIGURE` |
| Assignment settings | `GET`/`PUT /api/admin/assignment-settings` | `ASSIGNMENT_CONFIGURE` |
| Audit | `GET /api/admin/audit-events`, `GET /api/admin/audit-events/{eventId}` | `AUDIT_VIEW` |
| Incident trail | `GET /api/incidents/{incidentId}/audit` | any authenticated user; **P2's `IncidentAccessPolicy` decides**; `AUDIT_VIEW` holders get the full row, others the reduced entry |
| Me | `GET /api/users/me` | any authenticated user (no id in the path, so no IDOR surface) |

All admin permissions are held only by `ADMIN`. `RouteAuthorizationMatrixIntegrationTest` discovers every `/api` route at
runtime and proves, per area and not merely "admin can do everything": each admin handler declares an HTTP method and a
`@PreAuthorize` naming exactly the area's permission; a principal holding **only** that permission reaches every route of the
area with a valid request (2xx); a principal holding only any *other* admin permission, one with no authority, and real
EMPLOYEE and TECHNICIAN users get 403 on every admin route **even with an invalid body**; anonymous callers get 401 on
every protected route. Every admin route must have a probe in the test, so a new route fails the build until it is covered.
The area-to-permission table lives in the test itself, independent of the code it checks.

## 4. What B9 changed in behaviour

1. **Authorization is now decided before request validation on admin routes** (`SecurityConfig`). Previously
   `@PreAuthorize` ran after body binding, so an authenticated employee or technician sending an invalid body to 19 admin
   endpoints received **400 with field-level validation messages** instead of 403. Valid requests were always refused with 403.
   The URL rules mirror the controller annotations (which stay as a second layer); an unlisted `/api/admin/**` path is
   ADMIN-only. Contract impact: non-admins now always get 403 (stricter, still an error). Admin behaviour is unchanged.
2. **A failing `AssignmentStrategyCatalog` no longer breaks reading configuration, and the three catalog states are explicit**
   (`AssignmentSettingsAdminService`, `StrategyCatalogGateway`). Added the **additive** response field
   `strategyCatalogStatus` (`AVAILABLE` | `ABSENT` | `FAILED`); `availableStrategies` is unchanged (keys when `AVAILABLE`,
   `[]` when available but empty, `null` otherwise). Before, a catalog that threw made `GET` fail, and one that threw
   *after* validation rolled back a valid `PUT`. A transactional catalog that threw inside the `GET`'s read transaction
   marked it rollback-only and the `GET` failed at commit (`UnexpectedRollbackException`, HTTP 500): reproduced, then fixed by
   asking the catalog through a `NOT_SUPPORTED` bean so it can never share the caller's transaction. `PUT` still fails
   closed before writing (`ABSENT` -> specific 503; `FAILED` -> the generic 500/503 for the exception's type) and reuses the
   key set it already fetched. Exception messages are never returned; the log line carries the exception class only.
3. **Admin authorization is test-proven per permission, not only per refusal.** `RouteAuthorizationMatrixIntegrationTest`
   now checks structure (every admin handler has an HTTP method and a `@PreAuthorize` naming exactly its area's permission),
   reach (a principal holding only that permission gets a 2xx from a valid request to every route) and refusal (a principal
   holding only any other admin permission, one with no authority, and real EMPLOYEE/TECHNICIAN users get 403 on every route
   even with an invalid body). A route requiring the wrong permission, or lacking a probe, fails the build.

## 5. Handoff by team

### P2
* **Catalogs:** inject `CategoryCatalog` / `ServiceCatalog`. Use `requireActive(id)` for new incidents (404 unknown, 400
  deactivated) and `findById` to display old ones. See "Category and service catalogs" in the integration guide.
* **Register `IncidentAccessPolicy`:** provide exactly one bean. Contract: throw `ResourceNotFound` if the incident does not
  exist; otherwise return whether the user may view it. P1 never re-implements it. Until it exists the incident trail is 503.
* **Audit gate:** the endpoint validates the id shape, then calls your policy, then decides *what* an allowed caller sees
  (full row for `AUDIT_VIEW`, reduced entry otherwise). `requireView` -> 403, your 404 passes through.
* **Lifecycle:** deactivated categories and services stay readable and are rejected for new work.

### P3
* **`UserDirectory`:** see "Technician profiles". `available` is an admin flag, **not** workload; `maxWorkload` is a ceiling,
  not a count; `escalationChain` is nearest-first and skips inactive managers; nothing inactive is returned. **Pending P3
  confirmation.**
* **Register `TechnicianWorkloadProvider`** and **`AssignmentStrategyCatalog`** (one bean each, in `src/main` of P3's module).
  P1 does not call the workload provider today. P1 looks the strategy catalog up lazily, so no P1 change is needed when it appears.
* **`AssignmentSettingsProvider.current()`** returns the stored key, default workload and per-category overrides. It does
  **not** re-verify keys at read time (they were verified at write time).
* **Catalog absent:** settings reads work with `strategyCatalogStatus: ABSENT` and `availableStrategies: null`; settings writes are 503. A *failing* catalog reads as `FAILED` (retry-worthy), never as a write permission. The seeded `LOAD_BASED`
  key is **unverified** until P3 registers it.

### P4
* **`SlaPolicyProvider.resolve(serviceId, priority)`:** active service-specific policy, else active global, else 422
  `MISSING_SLA_CONFIG`; unknown service 404; an **inactive service still resolves**; durations are never invented. A global
  policy is not guaranteed for every priority (an admin may deactivate it), so handle the 422.
  **Transactions:** `resolve` joins the caller's transaction. `MissingSlaConfiguration` and `ServiceNotFound` are expected
  domain outcomes and do **not** mark it rollback-only, so P4 may catch either and continue. Any other exception, including
  database/infrastructure failures, keeps normal rollback behaviour and must not be caught and ignored.
* **Boundary:** P1 stores and serves configuration (`responseMinutes`, `resolutionMinutes`, `warningThresholdPct`). Timers,
  breach detection, escalation, priority calculation and notifications are P4's.
* **`listAdmins()`** is the final escalation tier (active administrators only).

## 6. Test-only implementations (never in `src/main`)

| Class | File | Purpose |
|---|---|---|
| `FakeIncidentAccessPolicy` (+ `FakePolicyConfig`) | `audit/IncidentAuditIntegrationTest.kt` | records calls, proves P1 delegates |
| `NoIncidentPolicyConfig` | `audit/IncidentAuditProviderUnavailableIntegrationTest.kt` | removes the bean to simulate "P2 absent" |
| `FakeStrategyCatalogConfig` | `admin/assignment/AssignmentSettingsIntegrationTest.kt` | present, answering catalog |
| `ControllableStrategyCatalog` (`@Transactional`), `ControllableIncidentPolicy` | `integration/ProviderFailureSemanticsIntegrationTest.kt` | empty / domain-throwing / infrastructure-throwing providers; the catalog joins the caller's transaction like a database-backed P3 bean would |

## 7. Deferred and unresolved

* **Deferred:** refresh tokens; failed-login auditing; distributed or per-IP rate limiting (login and registration are
  unthrottled: put a gateway or ingress limit in front until it is built); a separate management port for Actuator (until
  then `/actuator/**` other than health and info is ADMIN-only on the main port; keep it off the public ingress);
  caching; keyset pagination.
* **Pending team confirmation:** technician contract (P3), `LOAD_BASED` default (P3), 422 and inactive-service semantics (P4),
  cross-priority SLA consistency (P4), `unassignedEscalationMinutes` ownership (P4), `defaultMaxWorkload` cap of 100.
* **Not verified by CI:** the B7 branch has no CI run of its own because the workflow only triggers on pushes to `main` and
  `feat/p1-admin-backend` and on pull requests to `main`. Its code was verified locally (see the B9 report).
