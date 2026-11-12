# Admin frontend ↔ backend contract (P1, batch B8)

Source of truth: the backend controllers, DTOs and rules under `backend/src/main/kotlin/com/itsm/admin/**` and
`audit/**`, read at commit `58915ad`. Every admin page in `frontend/src/features/admin` talks to these endpoints through
one path: `lib/api.ts#request` → `features/admin/services/http.ts#call` → a per-module `*-api.ts`. There is no mock layer
and no localStorage persistence left in the admin area.

Conventions common to all rows:

* Base `NEXT_PUBLIC_API_URL`; `Authorization: Bearer <JWT>` added by `request`. Ids are **numbers**. Timestamps are ISO strings.
* Lists return `{items, page (0-based), size, totalItems, totalPages}`; `size` 1–100. Sort `dir` is `asc|desc`.
* Errors are RFC 7807 `problem+json` with `code`, `message`/`detail`, optional `errors: [{field, message}]`.
* **Concurrency, by resource.**
  * **Users and technicians:** every change requires the `version` last read, including activate/deactivate (a deactivation
    there has side effects: session revocation, reporting-line rules). A stale `version` → 409 `CONCURRENT_MODIFICATION`.
    Reset-password and revoke-sessions on users carry no version.
  * **Categories, IT services, SLA policies:** `PUT` requires `version` (stale → 409). **Activate/deactivate take no
    version, by design:** they are idempotent *target-state* operations ("make it inactive"), so repeating one answers 200
    and changes nothing (verified against a real backend). Categories and services serialise them under an advisory lock and
    re-check their invariants inside it; SLA policies rely on the V13 unique indexes. A concurrent edit still loses
    cleanly (entities carry `@Version`, a lost race is a 409). The one consequence: a toggle acts on whatever the stored
    state is *now*, not on the snapshot the admin saw.
* Every admin permission is held by ADMIN only; reads included. Anonymous → 401, non-admin → 403. **Backend contract since B9:** the 403 is decided at the URL layer *before* the body is read, so a non-admin sending an
  invalid body gets 403, not a 400 with validation detail. Before B9 the same request answered 400. The frontend
  treats both as "no access" and never shows a validation message from a refused call.
* None of these endpoints has DELETE: records are deactivated. Every mutation below publishes a persistent audit event
  (B2). The frontend never writes audit records.

## Users — `USER_MANAGE` — `/api/admin/users`

| Operation | Method & path | Request | Response | Notes |
|---|---|---|---|---|
| List | `GET /` `page,size,search,role,active,sort,dir` | – | page of `AdminUser` | search = name or email; sort `email\|name\|role\|createdAt\|active` (default `email`; UI sends `name`) |
| Get | `GET /{id}` | – | `AdminUser` | |
| Create | `POST /` | `{email, name, password(8–128), role}` | 201 `AdminUser` | 409 `DUPLICATE_RESOURCE` email |
| Update | `PUT /{id}` | `{email, name, version}` | `AdminUser` | 409 stale / duplicate |
| Change role | `PUT /{id}/role` | `{role, version}` | `AdminUser` | revokes sessions; 409 self-change, 409 last active admin |
| Activate / deactivate | `POST /{id}/activate\|deactivate` | `{version}` | `AdminUser` | 409 self-deactivate, 409 last active admin |
| Reset password | `POST /{id}/reset-password` | `{password}` | `AdminUser` | **no version**; revokes sessions |
| Revoke sessions | `POST /{id}/revoke-sessions` | none | `AdminUser` | **no version** |

`AdminUser = {id, email, name, role: EMPLOYEE|TECHNICIAN|ADMIN, active, version, createdAt, updatedAt}`. No password, hash or token version is ever returned.

## Technicians — `USER_MANAGE` — `/api/admin/technicians` (path id = **user id**)

| Operation | Method & path | Request | Response | Notes |
|---|---|---|---|---|
| List | `GET /` `page,size,search,active,available,teamLead,categoryId,sort,dir` | – | page of `Technician` | search = name or email; sort `name\|email\|maxWorkload\|experienceYears\|createdAt` |
| Get | `GET /{userId}` | – | `Technician` | |
| Create | `POST /` | `{userId, maxWorkload(1–100), experienceYears(0–60), available, isTeamLead, reportsToUserId?, skills?[{categoryId, proficiency 1–5}]}` | 201 | user must be an **active TECHNICIAN** (409), one profile per user (409 duplicate) |
| Update details | `PUT /{userId}` | `{maxWorkload, experienceYears, available, isTeamLead, reportsToUserId (null clears), version}` | `Technician` | 409 cycle / team-lead with reports |
| Replace skills | `PUT /{userId}/skills` | `{skills:[…], version}` | `Technician` | whole set; `[]` removes all; duplicates rejected |
| Activate / deactivate | `POST /{userId}/activate\|deactivate` | `{version}` | `Technician` | 409 while active technicians report to them / user no longer TECHNICIAN |

The profile-creation picker uses only these capabilities: `GET /users?role=TECHNICIAN&active=true&search=…&size=100` for candidates, and `GET /technicians?search=…&size=100` (same term; both search name or email) to identify who already has a profile. Those users are listed but disabled ("already has a profile"). More than 100 matches → the dialog says to refine the search. A duplicate the lists did not show (another administrator was first) is still refused by the server (409 `DUPLICATE_RESOURCE`), and the form is kept so a different user can be chosen.

`Technician = {userId, name, email, userRole, userActive, active, available, maxWorkload, experienceYears, isTeamLead, reportsToUserId, skills:[{categoryId, categoryCode, categoryName, categoryActive, proficiency}], version, createdAt, updatedAt}`.
`available` is an administrator's eligibility flag and `maxWorkload` a configured ceiling. **There is no live workload, team, shift or skill level**, and the UI shows none.

## Incident categories — `CATALOG_MANAGE` — `/api/admin/categories`

| Operation | Method & path | Request | Response | Notes |
|---|---|---|---|---|
| List | `GET /` `page,size,search,active,sort,dir` | – | page of `Category` | search = name, code, description, keyword; sort `name\|code\|updatedAt\|createdAt` |
| Get | `GET /{id}` | – | `Category` | |
| Create | `POST /` | `{code, name, description?, keywords[1–15]}` | 201 | code `^[A-Z][A-Z0-9_]{0,39}$`, **immutable**, unique ignoring case (409); name 3–60 unique (409); keywords 2–30 chars, `[a-z0-9][a-z0-9 '&./+-]*`, normalised, no duplicates |
| Update | `PUT /{id}` | `{name, description?, keywords, version}` | `Category` | code cannot be sent/changed |
| Activate / deactivate | `POST /{id}/activate\|deactivate` | none | `Category` | **no version, idempotent**; 409 for the last active category; activating also needs the category's keywords to be free (below) |

**Keywords are unique across ACTIVE categories.** Creating, editing or reactivating a category whose keyword is already used by another active category → **409 `CONFLICT`** ("The keyword 'x' is already used by the active category 'Y'…"). The UI shows that message on the form and keeps what was typed; it cannot pre-check it (no endpoint exposes it).

`Category = {id, code, name, description|null, keywords(sorted), active, version, createdAt, updatedAt}`. No default priority (P4 owns priority).

## IT services — `CATALOG_MANAGE` — `/api/admin/services`

| Operation | Method & path | Request | Response | Notes |
|---|---|---|---|---|
| List | `GET /` `page,size,search,active,criticality,ownerTeam,sort,dir` | – | page of `ItService` | `ownerTeam` exact, case-insensitive; sort `name\|businessCriticality\|updatedAt\|createdAt` |
| Get | `GET /{id}` | – | `ItService` | 404 `SERVICE_NOT_FOUND` |
| Create | `POST /` | `{name(3–60), description?, businessCriticality 1–5, ownerTeam?, dependsOnServiceIds?[≤50]}` | 201 | 409 duplicate name |
| Update | `PUT /{id}` | same + `version` (dependency list required) | `ItService` | 409 dependency cycle; 400 self dependency |
| Activate / deactivate | `POST /{id}/activate\|deactivate` | none | `ItService` | **no version, idempotent**; 409 while an active service depends on it |

`ItService = {id, name, description|null, businessCriticality, ownerTeam|null, active, dependsOn:[ServiceRef], dependents:[ServiceRef], version, createdAt, updatedAt}`; `ServiceRef = {id, name, active}`. `dependents` are services that directly depend on this one (active and inactive).

## SLA policies — `SLA_CONFIGURE` — `/api/admin/sla-policies`

| Operation | Method & path | Request | Response | Notes |
|---|---|---|---|---|
| List | `GET /` `page,size,search,active,priority,serviceId,global,sort,dir` | – | page of `SlaPolicy` | search = **service name only**: a global policy has no service name, so it never matches a search. The page says so whenever a search is active, and points to Scope → Global only; sort `priority\|responseMinutes\|resolutionMinutes\|updatedAt\|createdAt` |
| Effective preview | `GET /effective?serviceId&priority` | – | `EffectiveSla` | service-specific active policy, else global, else **422 `MISSING_SLA_CONFIG`**; 404 unknown service |
| Get | `GET /{id}` | – | `SlaPolicy` | |
| Create | `POST /` | `{serviceId? (null = global), priority P1–P4, responseMinutes, resolutionMinutes, warningThresholdPct?}` | 201 | response ≥ 1, resolution > response, ≤ 43 200 min; threshold 1–99 (default 80); 409 if the scope is taken; service must be active |
| Update | `PUT /{id}` | `{responseMinutes, resolutionMinutes, warningThresholdPct?, version}` | `SlaPolicy` | service and priority are immutable; omitted threshold keeps the current one |
| Activate / deactivate | `POST /{id}/activate\|deactivate` | none | `SlaPolicy` | **no version, idempotent**; activate → 409 if scope taken |

`SlaPolicy = {id, service: ServiceRef|null, priority, responseMinutes, resolutionMinutes, warningThresholdPct, active, version, createdAt, updatedAt}`.
`EffectiveSla = {serviceId, priority, scope: SERVICE|GLOBAL, policyId, responseMinutes, resolutionMinutes, warningThresholdPct}`. Configuration only: nothing is enforced.

## Assignment settings — `ASSIGNMENT_CONFIGURE` — `/api/admin/assignment-settings`

| Operation | Method & path | Request | Response | Notes |
|---|---|---|---|---|
| Read | `GET /` | – | `AssignmentSettings` | always exists (seeded default); never fails because the catalog does |
| Replace | `PUT /` | `{activeStrategy, defaultMaxWorkload 1–100, categoryOverrides:[{categoryId, strategy}] (≤100, complete set), version}` | `AssignmentSettings` | every strategy key is validated against P3's catalog (below); 409 stale |

`AssignmentSettings = {activeStrategy, defaultMaxWorkload, categoryOverrides:[{categoryId, categoryName, categoryActive, strategy}], strategyCatalogStatus: AVAILABLE|ABSENT|FAILED, availableStrategies: string[]|null, version, updatedAt, updatedByUserId|null}`.

**The three catalog states** (`strategyCatalogStatus` is the source of truth; `availableStrategies` is an array exactly when it is `AVAILABLE`). This is the **backend contract since B9** (merged here, not yet deployed); a backend without the field is read from the list alone (array → AVAILABLE, null → ABSENT), and a response whose status and list contradict each other is refused.

| Status | `availableStrategies` | `PUT` | The page |
|---|---|---|---|
| `AVAILABLE`, keys | the keys | validated; unknown key → 400 on its field | **Editable.** Only the registered keys are selectable |
| `AVAILABLE`, empty | `[]` | every `PUT` → 400 | **Read-only:** "No assignment strategies are currently registered" |
| `ABSENT` | `null` | 503 `SERVICE_UNAVAILABLE` | **Read-only:** "has not been deployed yet" |
| `FAILED` | `null` | generic 500/503, no detail | **Read-only:** "could not be reached… try again", with a retry button; never says "not deployed" |

`null` is never read as "no strategies exist". The admin overview raises a separate attention item for ABSENT, FAILED and AVAILABLE-but-empty.

**Strategy choices are limited to registered strategies.** No strategy name is hardcoded anywhere in the frontend. A **saved** active or override key that is not registered (the seeded `LOAD_BASED` is unverified until P3 registers it) is kept and shown as `KEY (not registered)` in a disabled entry: it is never offered as a new choice and never silently replaced. The page explains it, and saving is refused on that field until a registered strategy is chosen. While the catalog is not `AVAILABLE`, saved keys are labelled `(unverified)` instead, because registration cannot be known.

## Audit (unchanged from B7) — `AUDIT_VIEW` — `/api/admin/audit-events`

`GET /` (filters `action, actorType, actorId, actorRole, entityType, entityId, incidentId, from, to, sort, dir`) and `GET /{eventId}`; read-only. See [audit-retrieval.md](audit-retrieval.md). The incident trail (`/api/incidents/{id}/audit`) has no admin UI.

## How the frontend maps failures (`services/http.ts`)

| Status / code | `ServiceError.code` | Shown |
|---|---|---|
| 401 | `UNAUTHENTICATED` | client session cleared (cached user dropped; the HttpOnly cookie is dead server-side), redirect to `/?redirect=<current path>` |
| 403 | `FORBIDDEN` | fixed text; audit page also wipes its rows |
| 404 | `NOT_FOUND` | server message |
| 400 | `VALIDATION` | server message; `errors[]` mapped onto fields by name (`keywords[2]`, `skills[0].proficiency`) |
| 409 `CONCURRENT_MODIFICATION` | `STALE` | server message plus **Keep my edits on the latest version** / **Discard mine and load latest** |
| 409 `DUPLICATE_RESOURCE` | `DUPLICATE` | server message, inline |
| 409 other | `CONFLICT` | server message (last-admin, self-lockout, cycle, referenced…) |
| 422 | `UNPROCESSABLE` | server message (missing SLA policy) |
| 503 | `DEPENDENCY_UNAVAILABLE` | server message (P3 catalog) |
| 5xx / network | `UNAVAILABLE` | fixed text, never server text |

Forms keep what was typed after any failure. Nothing updates optimistically: lists reload after a confirmed success.

## Mock → backend

| Feature | Old mock | Backend endpoint | Status | Remaining limitations |
|---|---|---|---|---|
| Users | `user-service.ts` over localStorage (department, INVITED/SUSPENDED, delete, bulk suspend, last sign-in) | `/api/admin/users…` | Integrated | Dropped features have no backend equivalent: department, invitations, last sign-in, delete, bulk actions. Accounts are deactivated. Initial passwords are set by the admin |
| Technicians | `technician-service.ts` (team, shift, skill names, level, availability enum, live workload) | `/api/admin/technicians…` | Integrated | No team/shift/live workload. The user picker searches active TECHNICIAN users (up to 100 per search) and marks those who already have a profile; a race is still caught by the server's 409 |
| Categories | `category-service.ts` (default priority, generated ids) | `/api/admin/categories…` | Integrated | No priority. Code is required and permanent |
| IT services | `service-service.ts` (string criticality, string ids, policy counts) | `/api/admin/services…` | Integrated | Dependency picker lists up to 100 services |
| SLA policies | `sla-service.ts` (required service, no global, no threshold) | `/api/admin/sla-policies…` | Integrated | Effective preview is read-only; targets are not enforced |
| Assignment | `assignment-service.ts` (invented MANUAL/ROUND_ROBIN/SKILL_BASED and per-strategy options) | `/api/admin/assignment-settings` | Integrated | Read-only, with a state-specific explanation, unless the catalog is AVAILABLE with keys. Only strategy, default workload and category overrides exist |
| Audit viewer | – (B7) | `/api/admin/audit-events…` | Integrated (B7) | Incident trail has no UI (P2) |
| Overview figures | `overview-service.ts` (mock counts, utilisation %) | the list endpoints above, page size 1 (`totalItems`) | Integrated | No utilisation figure (no live workload exists) |
| Overview "Recent changes" | mock local change log | `/api/admin/audit-events` (latest 6) | Integrated | Now authoritative, so the "Demo data" label is gone: no mock mutation flow remains |
| Mock store, seed data, `recordAudit` | `services/store.ts`, `data/seed*.ts`, `audit-service.ts` | – | **Deleted** | – |

## Backend observations (for B9; nothing was worked around)

1. There is no "users without a technician profile" filter. B8 identifies profiles through the technicians list with the same search term, which is complete up to 100 matches per search; beyond that the server's 409 is the safety net.
2. `GET /sla-policies?search=` filters by service name, so it can never return global policies (documented and surfaced in the UI).
3. ~~The B9 matrix said every update "must send the one last read".~~ Resolved in the B8+B9 merge: the matrix now states that catalog activate/deactivate are version-less idempotent target-state operations, while users and technicians require the version.
4. `CategoryRules` KDoc still says it matches `features/admin/services/category-service.ts`, which no longer exists.
5. `AdminUserResponse.createdAt` is a `String` while `updatedAt` is an `Instant` (both serialise as ISO text, so harmless).
