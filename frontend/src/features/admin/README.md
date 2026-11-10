# Admin workspace (P1: Access & Administration)

Routes live in `src/app/admin/*`; everything else is in this folder.

| Folder | Contents |
|---|---|
| `services/` | `http.ts` (the one admin HTTP path: query encoding, JSON, error mapping) and one typed `*-api.ts` per domain with its request builders and client-side validation. |
| `components/`, `hooks/` | Shared admin UI (shell, confirm dialog, filters, pagination, states, toasts, `FormError`) and `useResource` / `useListState`. |
| `users/`, `technicians/`, `categories/`, `it-services/`, `sla/`, `assignment/`, `audit/`, `overview/` | Pages and their dialogs. |

## Backend integration

Every admin page reads and writes the real backend. The endpoint-by-endpoint contract, the error mapping and the list of
dropped mock-only features are in [`docs/architecture/admin-frontend-contract.md`](../../../../docs/architecture/admin-frontend-contract.md).
Rules of thumb for changing anything here:

- Go through `services/http.ts#call` (which wraps the app's single `lib/api.ts#request`). Do not add another client.
- Types mirror the backend JSON: numeric ids, `version`, no frontend-only fields. If the backend lacks something,
  document it; do not invent it.
- Client validation mirrors the backend rules for fast feedback; the server stays authoritative, and its field
  errors are shown on the matching field.
- A failed save never clears the form. A stale `version` (409) offers "Keep my edits on the latest version" or
  "Discard mine and load latest". Lists reload after a confirmed success; nothing updates optimistically.
- Nothing is deleted: accounts, profiles, categories, services and policies are deactivated.
- No mock layer, `localStorage` data or simulated latency remains in this folder.

## Audit log

- The frontend **never writes** audit events; the backend publishes one for every successful mutation.
- **Audit logs is the authoritative view** (`services/audit-api.ts`, `GET /api/admin/audit-events`; contract in
  `docs/architecture/audit-retrieval.md`). It is read-only: the client has no write method and the endpoints are GET only.
- The overview's "Recent changes" card reads the latest six events from the same endpoint.

## Ownership boundaries

- **Assignment settings** store configuration only: the strategy key, a default workload ceiling and per-category
  overrides. Strategy keys come only from P3's registered catalog (`availableStrategies`); none is hardcoded here. The page
  follows `strategyCatalogStatus`: editable only when the catalog is AVAILABLE with keys; read-only, with its own
  explanation, when it is ABSENT (not deployed), FAILED (unreachable, retry) or AVAILABLE but empty. A saved key that is no
  longer registered stays visible as "(not registered)" and is never selectable or silently replaced. P3 owns the algorithms, workload calculations and
  assignment execution.
- **Technician profiles** carry an administrator's `available` eligibility flag and a configured `maxWorkload`
  ceiling. Neither is live workload and neither means "assignable right now".
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

### Provisioning

The backend issues the canonical roles `EMPLOYEE`, `TECHNICIAN` and `ADMIN`. The first `ADMIN` is created by the
one-time backend bootstrap command; further admins are created by an existing `ADMIN` under Users. Public registration
always creates an `EMPLOYEE` and never accepts a role (the frontend form sends only `email`, `name` and `password`).

There is deliberately no development bypass. The hermetic Playwright suite reaches the admin pages through ADMIN
personas and a stateful fake of the admin API (`e2e/fixtures.ts`, `e2e/admin-fake.ts`); in live mode, tests that need a
non-`USER` role are skipped.

## Token storage (backend dependency)

The token is still kept in localStorage plus a readable `SameSite=Lax` cookie (`Secure` over HTTPS only, so plain
`http://localhost` keeps working). Moving to HttpOnly cookies needs coordinated backend work: HttpOnly `Secure`
`SameSite` refresh cookies, refresh-token rotation, CSRF protection, and a short-lived access token. It is out of
scope for the frontend alone.
