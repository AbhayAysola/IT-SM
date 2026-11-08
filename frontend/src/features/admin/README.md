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
  exercise error states. It exists only because the data is mocked.

## Audit log

- Every successful mutation appends one entry through `recordAudit`. Rejected or failed mutations write nothing.
  Updates and status changes record before/after values for the changed fields only.
- The viewer's `auditService` exposes only `recent`, `list` and `get`. There is no edit or delete anywhere in the app.
- Seeded history is flagged `seed: true` and labelled "Demo data". Entries written before before/after recording
  existed show "No before/after values were recorded".
- **Not tamper-proof.** The log is a mock in browser storage, so anyone with devtools can change it. Real
  append-only guarantees must come from the backend.

## Ownership boundaries

- **Assignment settings** store configuration only. `MANUAL`, `ROUND_ROBIN` and `SKILL_BASED`, and their options,
  are this frontend's own placeholder names. They are not a P3 contract. P3 owns the algorithms, workload
  calculations and assignment execution.
- **SLA policies** are configuration only. P4 owns timers, breach detection and escalation.
- Incident creation, categorisation and incident access policy belong to P2.

## Access control (temporary)

`/admin/*` is wrapped in the existing `AuthGuard`, which only checks that a token exists. The backend currently
issues a single `USER` role, so the admin pages are **not** role-gated, and nothing here is a security boundary.
Once the backend exposes an administrator role, gate the admin routes and the navbar link on it, and enforce the
same checks server-side.
