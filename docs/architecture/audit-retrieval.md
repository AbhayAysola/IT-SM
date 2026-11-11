# Audit retrieval API (P1, batch B7)

Read-only access to the append-only `audit_events` table (V10, written by `AuditEventPersistenceListener`). Nothing in
this batch changes how audit rows are written, the schema, or the immutability guards.

Code: `com.itsm.audit` — `AuditEventReader` (SELECT-only queries), `AuditQueryService`, `AuditAdminController`,
`IncidentAuditController`, `AuditQueryDtos`.

## Endpoints

| Method + path | Who | Purpose |
|---|---|---|
| `GET /api/admin/audit-events` | `AUDIT_VIEW` (ADMIN) | Paged, filtered, sorted list |
| `GET /api/admin/audit-events/{eventId}` | `AUDIT_VIEW` (ADMIN) | One event by its public id |
| `GET /api/incidents/{incidentId}/audit` | any authenticated user **whom P2's `IncidentAccessPolicy` allows** | One incident's trail |

There are no other methods. `POST/PUT/PATCH/DELETE` on these paths answer `405`; the database trigger would reject them
regardless. Anonymous: `401`; authenticated without `AUDIT_VIEW` on the admin paths: `403` (problem+json, codes as in
foundation.md).

## Row shape (`AuditEventResponse`)

```json
{
  "id": "2f0c…",                       // the row's event_id (UUID). The internal bigint id is never exposed.
  "occurredAt": "2026-03-10T12:00:00Z",
  "actor": { "type": "USER", "id": 12, "role": "ADMIN", "name": null },
  "action": "SLA_POLICY_UPDATED",
  "entityType": "SLA_POLICY",
  "entityId": "7",
  "incidentId": null,
  "previousState": null,
  "newState": null,
  "description": "…",
  "metadata": { "changedFields": ["name"], "count": 3 }
}
```

* **Actor.** Exactly what the table stores: a user is `type=USER` with stable `id` and `role` (no email, no name — they
  are not stored); a system job is `type=SYSTEM` with `name` (e.g. `SLA_MONITOR`) and null id/role. Names of people are
  **not** resolved: a viewer shows "Administrator #12". Resolving ids to people is deferred (see below).
* **System-generated events are included** in the admin list. Filter them with `actorType=SYSTEM` or exclude them with
  `actorType=USER`.
* **Metadata.** Stored as JSONB, validated on write (scalars and flat lists of scalars). On read it is re-normalised to
  that same shape: nulls, nested objects and non-scalar list items are **dropped** (not rewritten), keys are sorted,
  the result is unmodifiable. Strings are returned verbatim (JSON-escaped, never interpreted).
* `recorded_at` (when the row was written) is not mapped by the B2 entity and is therefore not exposed. `occurredAt` is
  the event's own timestamp.
* "Event type" in the brief is the table's `action` column; there is no separate event-type field.

## Admin list: query parameters

All optional. Unknown values are a `400 INVALID_INPUT` with a per-field `errors[]` list; nothing is silently ignored.
Blank values mean "no filter". Filters combine with AND. All matches are **exact** (no prefix, substring or
case-insensitive matching, except `actorRole`/`actorType`, which accept any case).

| Param | Meaning | Format |
|---|---|---|
| `action` | `action` equals | `UPPER_SNAKE_CASE`, 3–60 chars |
| `actorType` | `USER` or `SYSTEM` | |
| `actorId` | `actor_id` equals | positive integer |
| `actorRole` | `actor_role` equals | `ADMIN`, `TECHNICIAN`, `EMPLOYEE` |
| `entityType` | `entity_type` equals | `UPPER_SNAKE_CASE`, 2–40 chars |
| `entityId` | `entity_id` equals — **direct matches only** | `[A-Za-z0-9_-]{1,40}` |
| `incidentId` | `incident_id` equals | `[A-Za-z0-9_-]{1,30}` |
| `from` | `occurredAt >= from` (inclusive) | ISO-8601 instant with offset, e.g. `2026-03-10T00:00:00Z` |
| `to` | `occurredAt < to` (**exclusive**) | same; `from > to` is a 400 (`from == to` is an empty range) |
| `page`, `size` | paging | `page >= 0`, `size` 1–100, default 20 |
| `sort`, `dir` | ordering | below |

**Entity filters are direct.** `entityId=INC7` matches rows whose own `entity_id` is `INC7`; it does **not** pull in rows
that merely belong to incident INC7 (`incident_id`). Use `incidentId=INC7` for that. Neither does any partial matching.

**Date boundaries.** Instants are normalised to UTC; offsets are honoured (encode `+` as `%2B`). Half-open
`[from, to)` means a whole UTC day is `from=2026-03-10T00:00:00Z&to=2026-03-11T00:00:00Z` — adjacent days never overlap
and nothing falls between them. Row timestamps keep their full precision.

**Sorting.** `sort` ∈ `occurredAt` (default), `action`, `entityType`, `actorRole`; `dir` ∈ `asc`, `desc` (default `desc`
on the admin list, `asc` on the incident trail). The ordering is always total: `occurredAt` sorts break ties by
internal id in the same direction; other sorts fall back to `occurredAt desc, id desc`. Paging is therefore stable and
repeatable, and newest-first uses `idx_audit_events_occurred_at (occurred_at DESC, id DESC)`.

**Paging.** Standard `PageResponse`: `items`, `page` (0-based), `size`, `totalItems`, `totalPages`. A page past the end
is an empty page, not an error. A `page`/`size` whose offset exceeds 2^31−1 is a 400 (enforced by `PageQuery`, like every list). One list call = one `COUNT` plus
one `LIMIT/OFFSET` select (asserted by a test); the table is never loaded into memory. Deep offsets and the exact count
cost more on a very large table; keyset paging is a possible later optimisation (deferred).

**Detail.** `{eventId}` is the UUID from the list. Malformed → `400`; unknown → `404 RESOURCE_NOT_FOUND`.

## Incident trail and `IncidentAccessPolicy`

`GET /api/incidents/{incidentId}/audit` returns rows with `incident_id = {incidentId}` (any entity type), oldest first by
default, same paging/sort params (`page`, `size`, `sort`, `dir`; no filters). Order of checks:

1. Authentication (`401`).
2. `incidentId` format (`400`).
3. **P2's `IncidentAccessPolicy.requireView(user, incidentId)`**, injected as `ObjectProvider<IncidentAccessPolicy>`:
   unknown incident → `404 RESOURCE_NOT_FOUND` (P2's exception), not allowed → `403 FORBIDDEN`. P1 contains **no**
   ownership/visibility rule of its own.
4. Only then is the audit table queried.

**Provider unavailable.** If no `IncidentAccessPolicy` bean exists the endpoint answers `503 SERVICE_UNAVAILABLE` for
**everyone, admins included**, for every incident id. There is no fallback rule and no empty `200`. "Provider missing"
(503) is therefore always distinguishable from "incident exists but has no audit events" (200, `items: []`).
(Same pattern as the assignment strategy catalog.)

**Two separate questions.** P2's policy answers only "may this caller see this incident?". Which *audit details* of that
incident they see is decided here, by `AUDIT_VIEW`:

| | `AUDIT_VIEW` (admin) | Incident viewer without it |
|---|---|---|
| Type | `AuditEventResponse` (full row) | `IncidentHistoryEntry` (separate class) |
| Fields | everything in "Row shape" | `id`, `occurredAt`, `action`, `actor{type, role}`, `previousState`, `newState`, `incidentId` |
| Actor | id, role, system job name | kind and role only (system actors: `SYSTEM`, no job name) |
| `description`, `metadata`, `entityType`, `entityId` | yes (metadata redacted as below) | **absent** (field not present, not null) |
| Sort keys | all four | `occurredAt`, `action` only (others → `400`) |

The reduced type exists on purpose: a field reaches a non-admin only by being added to it deliberately. Redaction
happens at the response boundary; stored rows are never rewritten.

**Metadata redaction (admin view too).** Producers are contractually forbidden to store credentials or personal data
(`AuditableEvent`), and the review of every current producer (users, auth, bootstrap, categories, IT services, SLA
policies, assignment settings) found none: keys are ids, role names, counts, booleans, changed-field names; the actor's
e-mail is never stored. As defence in depth `SafeMetadata` additionally drops entries whose key contains
`password|passwd|secret|token|credential|hash|jwt|authorization|apikey|email|phone`, and string values that look like an
e-mail address or a JWT, plus anything non-scalar (nested objects, nulls).

**Known residual risk: `description`.** It is free text (≤1000 chars). Current descriptions embed category/service/SLA
names and field names, not personal data, but the contract only says "do not embed secrets". Admins see it; incident
viewers never do.

**Not production-integrated.** Incident audit works end to end only once P2 supplies an `IncidentAccessPolicy` bean and
verifies it. Until then the endpoint is 503 for everyone, and nothing in `main` emits events with an `incident_id`
(all current events concern USER, CATEGORY, IT_SERVICE, SLA_POLICY, ASSIGNMENT_SETTINGS), so P2's producers are also
unreviewed here. Tests use a test-only `FakeIncidentAccessPolicy` (`src/test`), which cannot reach production.

**Current status (this branch): P2 has not implemented the SPI** (only the interface in `ProviderInterfaces.kt` exists),
so this endpoint answers 503 in a real deployment until P2's bean lands. Tests use a stand-in policy that exists only in
`src/test`. When P2's bean is registered nothing here changes.

## Frontend

`/admin/audit-logs` (route already guarded by `AuthGuard requiredRole="ADMIN"`) now reads the API through
`frontend/src/features/admin/services/audit-api.ts`; the former localStorage `list`/`get` are no longer used by the page.

* Filters: action, entity type, entity ID, actor ID, actor role (incl. "System" → `actorType=SYSTEM`), UTC date range.
  Action/entity type accept free text and are normalised to the stored code (`user created` → `USER_CREATED`);
  there is no list of known values to pick from because the backend has no such endpoint (and P2–P4 will add actions).
  The free-text search box was removed: the API has no full-text search.
* A date `To` of `2026-11-04` is sent as `to=2026-11-05T00:00:00.000Z` (exclusive), so the UI keeps its inclusive meaning.
* Invalid filters are explained next to the field and **never sent**.
* Columns sort by `occurredAt`, `actorRole`, `action`, `entityType`. Page size 10.
* States: loading skeleton, empty ("no entries yet" vs "no match"), error with retry (5xx/network show a fixed safe
  message, never server text), `403` → permission message and **all rows removed**, no retry; `401` → token cleared and
  redirect to sign-in (as the route guard does).
* Detail dialog loads `GET …/{id}`; metadata is rendered as a field/value table of plain text.
* **Audit logs is the authoritative view of persisted events.** The overview's "Recent changes" card reads the latest
  six events from the same endpoint (B8). Every admin mutation flow now calls a backend endpoint that publishes a
  persistent audit event, so the old browser-local mock log and its "Demo data" label no longer exist. The frontend
  never writes audit events. See [admin-frontend-contract.md](admin-frontend-contract.md).
* The frontend does not call the incident trail endpoint yet (no incident UI exists; that is P2's surface).

## Tests

Backend (PostgreSQL Testcontainers): `AuditRetrievalIntegrationTest`, `IncidentAuditIntegrationTest`,
`IncidentAuditProviderUnavailableIntegrationTest` (removes the provider bean from its context, so it stays valid after P2
merges), `SafeMetadataTest`. Frontend: `e2e/admin-governance.spec.ts` ("audit logs (backend API)") against a hermetic
fake of the endpoint in `e2e/fixtures.ts` (the real semantics are covered by the backend tests).

## Deferred / open

* **P2:** register `IncidentAccessPolicy` and verify it. Review P2's incident event producers (metadata, descriptions).
* **Product decision (open):** may incident viewers ever see more than the reduced projection? Specifically (a) the
  `description`, (b) which `metadata` keys, via a per-action allowlist, (c) the acting person's name instead of just the
  role (reporters usually know the technician), (d) whether some actions are internal-only (e.g. internal notes or
  routing details) and must be hidden from reporters. Until decided, only the reduced projection is exposed.
* **P2:** an incident UI that calls the trail endpoint.
* Resolving actor ids to display names (needs a deliberate decision about exposing personal data to the audit viewer).
* Exposing `recorded_at` (requires mapping the column on the B2 entity).
* A "distinct actions / entity types" endpoint so filters can be dropdowns.
* Keyset pagination / approximate counts if the table grows very large.
