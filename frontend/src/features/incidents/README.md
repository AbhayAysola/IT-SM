# Incident workspace (P3: lifecycle, assignment, investigation, feedback)

Routes: `/incidents` (role-aware work queue) and `/incidents/[id]` (detail). Code lives here; the routes in
`src/app/incidents/*` only mount it. Backend rules this UI follows are documented in
[`docs/architecture/p3-lifecycle.md`](../../../../docs/architecture/p3-lifecycle.md),
[`p3-assignment.md`](../../../../docs/architecture/p3-assignment.md),
[`p3-investigation.md`](../../../../docs/architecture/p3-investigation.md) and
[`p3-feedback.md`](../../../../docs/architecture/p3-feedback.md).

## What each role sees

| Role | List | On an incident |
|---|---|---|
| Employee (reporter) | **My incidents**: Open / Done, plain-language status, "Needs you" when a reply or confirmation is wanted | Reads the conversation (never internal notes), replies to questions, confirms the fix or says it is not fixed (reason required), rates once |
| Technician | **My incidents**: Active / Waiting on reporter / Done, what needs them first | Accepts, starts, asks the reporter a question, resolves; adds notes (internal unless shared); sees past fixes for similar incidents |
| Administrator | **Incidents**: Awaiting assignment / In progress / Done | Assigns and reassigns with scored, explained suggestions; closes confirmed incidents; can do any legal move |

The legacy backend role `USER` is treated as an employee, and so is any unrecognised role (least privilege, never an
accidental upgrade). **Against the real backend only the employee view is reachable today**, because it issues only
`USER`; the technician and administrator views are exercised through the e2e fake API.

## Layers

| Folder | Contents |
|---|---|
| `model/` | `lifecycle.ts` (transitions, permissions, ownership, wording), `recommender.ts` (eligibility + scoring), `types.ts`. **A copy of the backend rules**, with the same check order (legality, permission, ownership, reason). |
| `services/` | `incident-service.ts` is the only thing pages call. `store.ts` persists to `localStorage`. Replace these with the real API and pages should not change. |
| `data/` | `seed.ts` builds the fictitious demo data. |
| `components/` | Timeline, dialogs, note and feedback forms, past-fixes panel, badges, and `workspace-shell.tsx` (sign-in guard, navbar, toasts, the signed-in `Actor`). |

## Mock persistence (temporary)

* **One demo data set per signed-in account**, under `itsm_p3_v1_world_<email>`. It is built from the account's role so
  each persona has work to do: a technician is assigned six incidents, an employee reported five, an admin sees all
  twelve with two awaiting assignment. Changing an account's role later does not rebuild its data; clear the key.
* Seed addresses use the reserved `campus.example` domain. Dates are fixed so the seed is deterministic.
* Malformed or outdated stored data is reseeded rather than crashing a page. Every call has simulated latency.
* `localStorage.itsm_p3_simulate_error = "1"` makes every call fail (used by e2e for error states). It can only cause
  failures, never grant access, and disappears with the mock layer.
* Storage is per browser profile; nothing is shared between users or devices. P2 will own real incident creation, so
  there is no "report an incident" screen here.
* The technician pool for assignment is read from the **admin workspace's mock data** (`itsm_admin_v1_technicians` and
  `_users`), so availability and workload limits edited in `/admin/technicians` change who is eligible.

## Intricacies

* **Check order matches the backend.** A move is rejected for legality (409-like) before permission, ownership, then a
  missing reason. A stale page that acts on an incident that has moved on gets "already moved on" and reloads.
* **Ownership is checked here too.** The assignee does work moves, the reporter does reporter moves, admins are exempt
  (they hold every permission). Anyone else gets "not found", never "forbidden", so an incident's existence is not revealed.
* **Reporters never receive internal entries**: the service filters them out; they are not merely hidden by CSS.
  Reassignment notes are always internal, the conversation always reporter-visible.
* **The page offers only moves the rules allow**, taken from `availableTransitions`, so there is no disabled-button
  guessing. `ACKNOWLEDGE` and `START_WORK` are one click; every other move goes through a dialog.
* **The technician-reporter gap is reproduced on purpose.** The backend gives `INCIDENT_VERIFY_OWN` to employees only, so a
  technician who reports an incident cannot verify, reopen or rate it. The UI mirrors that instead of promising what the
  API will refuse (see `p3-feedback.md`; a model test pins it).
* **Admins cannot give feedback**: it is the reporter's voice.
* **Recommendations are a copy of the backend scorer**, fed by a placeholder: the admin technician profile has skill
  *names* and one level, the backend has a proficiency 1 to 5 *per category*. `LEVEL_PROFICIENCY` and `LEVEL_YEARS` in
  `recommender.ts` bridge the two (junior 2, intermediate 3, senior 5; 1, 4 and 8 years). Only `AVAILABLE` active
  technicians are eligible; `BUSY`, `OFF_SHIFT` and `ON_LEAVE` are not. The admin form's `fallbackStrategy` is **not** modelled
  (the backend settings view has no such field; open question in `p3-assignment.md`).
* **Past fixes** are closed or verified incidents in the same category with a resolution entry, same service first,
  newest next, at most three, shown only to staff on an active incident. They are advice, labelled as such, and not links:
  a technician may not be allowed to open someone else's incident.
* **Dialogs restore focus themselves.** Radix returns focus only to its own `DialogTrigger`; these dialogs are opened from
  ordinary buttons, so `returnFocus` hands focus back to the opener. They stay mounted while closing for the same reason.
* **Real headings.** The shared `CardTitle` renders a `div`, so cards here use `CardHeading` (an `h2`). Tests that assert
  something is *absent* first assert that the page rendered, so they cannot pass on an empty page.
* **Cross-feature imports.** This feature reuses the admin workspace's `ToastProvider`, `PageHeader`, `FormField`,
  `states`, `useResource` and `PriorityBadge`, and its technician mock data. Moving them to shared folders would touch the
  admin workspace (P1's), so it is left for a later cleanup.
* **Language.** Employee-facing wording avoids jargon (`PENDING_INFO` reads "Waiting for your reply"); staff see the
  precise terms. Status is always words as well as colour.

## Not built

Reporting a new incident (P2), audit-log integration (the admin audit viewer has no incident entity yet; real audit
comes from the backend `AuditPublisher`), notifications, SLA clocks (P4), attachments, the real API.

## Tests

`e2e/incident-model.spec.ts` (15 pure checks of the rules and recommender, no pages) and `e2e/incidents.spec.ts`
(32 browser tests: every role's flows, access, errors, axe on lists, details and dialogs, focus handling, phone width).
