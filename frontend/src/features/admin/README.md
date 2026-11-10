# Admin workspace (P1: Access & Administration)

Routes live in `src/app/admin/*`; everything else is in this folder.

| Folder | Contents |
|---|---|
| `services/` | Typed services, one per domain. All business rules and validation live here. |
| `data/` | Seed data for a fresh install. |
| `components/`, `hooks/` | Shared admin UI (shell, confirm dialog, filters, pagination, states, toasts) and `useResource`. |
| `users/`, `technicians/`, `categories/`, `it-services/`, `sla/`, `assignment/`, `audit/`, `overview/` | Pages and their dialogs. They call services and render; they hold no business rules. |

## Mock persistence (temporary)

There are no admin endpoints in the backend yet, so services persist to `localStorage` through `services/store.ts`
(keys `itsm_admin_v1_*`, with an in-memory fallback). Connecting the backend means re-implementing the service
functions; pages and components should not need to change. No API endpoints have been invented.

- Malformed or outdated stored data is reseeded or ignored rather than crashing a page.
- Every call has simulated latency so loading states are real.
- `localStorage.itsm_admin_simulate_error = "1"` makes every service call fail. The Playwright suite uses it to
  exercise error states. It exists only because the data is mocked: it can only cause failures, never grant access,
  and it disappears with the mock layer.
- Seed data is fictitious. Seeded email addresses use the reserved `campus.example` domain (RFC 2606), so they cannot
  belong to anyone.
- Storage is per browser profile: data is not shared between users or devices, and clearing site data resets it.

## Audit log

- Every successful mutation appends one entry through `recordAudit`. Rejected or failed mutations write nothing.
  Updates and status changes record before/after values for the changed fields only.
- **The Audit logs page reads the backend** (`services/audit-api.ts`, `GET /api/admin/audit-events`; contract in `docs/architecture/audit-retrieval.md`). It is read-only: the client has no write method and the endpoints are GET only.
- The mock `auditService` (`recent`, plus the now-unused `list`/`get`) still backs the overview's "Recent changes" card, which is labelled **Demo data** and is not the audit history (Audit logs is). Mutations of the other, still-mocked pages append to that local log through `recordAudit`, so they do **not** appear on the Audit logs page until those pages are connected.
- (Mock log only.) Seeded history is flagged `seed: true`. Entries written before before/after recording
  existed show "No before/after values were recorded".
- **Not tamper-proof.** The log is a mock in browser storage, so anyone with devtools can change it. Real
  append-only guarantees must come from the backend.

## Ownership boundaries

- **Assignment settings** store configuration only. `MANUAL`, `ROUND_ROBIN` and `SKILL_BASED`, and their options,
  are this frontend's own placeholder names. They are not a P3 contract. P3 owns the algorithms, workload
  calculations and assignment execution.
- **SLA policies** are configuration only. P4 owns timers, breach detection and escalation.
- Incident creation, categorisation and incident access policy belong to P2.

## Access control

`/admin/*` is wrapped in `AuthGuard requiredRole="ADMIN"` (in `components/admin-shell.tsx`, the shared admin layout).

- The role comes from `GET /api/users/me` on every visit. It is never read from localStorage, the cached user or the
  JWT, and nothing under `/admin` renders until the backend answers.
- No token, or a 401: the token is cleared and the user is sent to sign-in. A 403, a non-`ADMIN` role, or a response
  without a role: an "Access denied" page. A network error or 5xx: "Couldn't verify your access" with a retry. None of
  these render admin content.
- The navbar's Administration link appears only after `/api/users/me` confirms `ADMIN`.

**This is UX, not security.** The backend must enforce authorization on every admin API.

### Backend dependency: nobody can open `/admin` yet

The backend still issues a single role, `USER` (`UserEntity.role` defaults to `"USER"`; there are no `EMPLOYEE`,
`TECHNICIAN` or `ADMIN` values). Until P1 backend work lands, the real application shows "Access denied" to every
account on `/admin`. That is intended. The agreed provisioning model:

- The first `ADMIN` is created by an explicit one-time backend bootstrap command.
- Further admins are created by an existing `ADMIN` through user management.
- Public registration always creates `EMPLOYEE` and never accepts a role. (The frontend form sends only
  `email`, `name` and `password`.)

There is deliberately no development bypass. The hermetic Playwright suite reaches the admin pages through ADMIN
personas in its fake API (`e2e/fixtures.ts`); in live mode, tests that need a non-`USER` role are skipped.

## Token storage (backend dependency)

The token is still kept in localStorage plus a readable `SameSite=Lax` cookie (`Secure` over HTTPS only, so plain
`http://localhost` keeps working). Moving to HttpOnly cookies needs coordinated backend work: HttpOnly `Secure`
`SameSite` refresh cookies, refresh-token rotation, CSRF protection, and a short-lived access token. It is out of
scope for the frontend alone.
