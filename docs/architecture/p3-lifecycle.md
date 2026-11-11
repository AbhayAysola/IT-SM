# P3: incident lifecycle state machine

Code: `backend/src/main/kotlin/com/itsm/lifecycle/`. Pure domain logic (no database, no Spring), so it is
unit-tested on its own and carries no dependency on P2's incident storage.

## States

`NEW -> CLASSIFIED -> ASSIGNED -> ACKNOWLEDGED -> IN_PROGRESS <-> PENDING_INFO`, then
`IN_PROGRESS -> RESOLVED -> VERIFIED -> CLOSED`. `CLOSED` is terminal. States cross module boundaries as
their UPPER_SNAKE_CASE name (`InvalidStateTransition`, audit `previousState`/`newState`).

## Transitions

The rules are one table, `IncidentTransition`. Each entry has the allowed source states, the target state, the
`Permission` the caller needs, the audit action to publish, and whether a reason is mandatory.

| Transition | From | To | Permission | Audit action | Reason |
|---|---|---|---|---|---|
| `CLASSIFY` | NEW | CLASSIFIED | `INCIDENT_CREATE` | `INCIDENT_CLASSIFIED` | |
| `ASSIGN` | CLASSIFIED | ASSIGNED | `INCIDENT_ASSIGN` | `INCIDENT_ASSIGNED` | |
| `REASSIGN` | ASSIGNED, ACKNOWLEDGED, IN_PROGRESS, PENDING_INFO | ASSIGNED | `INCIDENT_ASSIGN` | `INCIDENT_REASSIGNED` | required |
| `ACKNOWLEDGE` | ASSIGNED | ACKNOWLEDGED | `INCIDENT_WORK` | `INCIDENT_ACKNOWLEDGED` | |
| `START_WORK` | ACKNOWLEDGED | IN_PROGRESS | `INCIDENT_WORK` | `INCIDENT_STATUS_CHANGED` | |
| `REQUEST_INFO` | IN_PROGRESS | PENDING_INFO | `INCIDENT_WORK` | `INCIDENT_STATUS_CHANGED` | required |
| `PROVIDE_INFO` | PENDING_INFO | IN_PROGRESS | `INCIDENT_VIEW_OWN` | `INCIDENT_STATUS_CHANGED` | |
| `RESOLVE` | IN_PROGRESS | RESOLVED | `INCIDENT_WORK` | `INCIDENT_RESOLVED` | required |
| `VERIFY` | RESOLVED | VERIFIED | `INCIDENT_VERIFY_OWN` | `INCIDENT_VERIFIED` | |
| `REOPEN` | RESOLVED | IN_PROGRESS | `INCIDENT_VERIFY_OWN` | `INCIDENT_STATUS_CHANGED` | required |
| `CLOSE` | VERIFIED | CLOSED | `INCIDENT_CLOSE` | `INCIDENT_CLOSED` | |

`ASSIGNED`, `ACKNOWLEDGED`, `IN_PROGRESS` and `PENDING_INFO` count toward a technician's workload
(`IncidentState.countsTowardWorkload`); the `TechnicianWorkloadProvider` will use that.

## Using it

```kotlin
@Transactional
fun resolve(incidentId: String, reason: String) {
    val incident = repo.require(incidentId)                     // P3 service, P2's data
    val r = IncidentStateMachine.apply(incident.state, IncidentTransition.RESOLVE, currentUser.current(), reason)
    incident.state = r.newState
    audit.publish(AuditableEvent(/* action = r.auditAction, previousState = r.previousState.name, ... */))
}
```

`apply` throws, in this order: `InvalidStateTransition` (409, not legal from the current state), then
`UnauthorizedOperation` (403, missing permission), then `InvalidInput` (400, missing or over-long reason).
Legality is checked first so a caller learns an incident has moved on whatever their permissions.

## What the state machine does not check

Permission is necessary, not sufficient: it does not know *whose* incident it is. The service must also confirm
the caller is the assigned technician (work transitions) or the reporter (`PROVIDE_INFO`, `VERIFY`, `REOPEN`),
using `IncidentAccessPolicy` from P2. Admins hold every permission, so they are exempt from that check.

## Design decisions and edge cases

* **Proposal, not specification.** The project spec lists the stages (report, classify, prioritise, assign,
  investigate, resolve, verify, close) but not the states. `PENDING_INFO` ("request more information") and
  `REOPEN` (the reporter says it is not fixed) are additions. Agree them with P2 and P4 before they build on this.
* **Order of checks.** Legality is checked before permission, so a caller acting on an incident that has already
  moved on gets 409 whatever their role. A permitted caller on a legal move who gives no reason gets 400 last.
* **Admins hold every permission**, so they may perform any legal transition. They are still bound by legality:
  there is no way to skip a state (NEW cannot go to CLOSED; RESOLVED cannot be closed unverified).
* **`REASSIGN` is the only transition that ends where it may start** (ASSIGNED -> ASSIGNED). It is allowed from every
  active state, always lands in `ASSIGNED` (the new technician must acknowledge), and requires a reason. Work
  already done stays in history; the incident restarts from `ASSIGNED`.
* **Loops.** `PENDING_INFO <-> IN_PROGRESS` (waiting on the reporter) and `RESOLVED -> IN_PROGRESS` (`REOPEN`).
  A test proves every state can still reach `CLOSED`, so no incident can get stuck.
* **`REOPEN` returns to `IN_PROGRESS`, not `ASSIGNED`.** The same technician continues; use `REASSIGN` first if the
  reporter wants someone else. The reason is mandatory so the history says why the fix failed.
* **`CLASSIFY` needs `INCIDENT_CREATE`**, held by every role, because intake (P2) does it on the reporter's behalf.
  `PROVIDE_INFO` needs only `INCIDENT_VIEW_OWN`, also held by every role. For both, **ownership is the real
  check and is the service's job** (see above); the permission only rules out anonymous callers.
* **No cancel or withdrawn state.** An employee who reported by mistake cannot withdraw an incident yet. Add a
  `CANCELLED` terminal state if the team wants that; it is one enum entry and one table row.
* **No automatic closing.** `VERIFIED -> CLOSED` needs `INCIDENT_CLOSE` (admin only today). A scheduled auto-close
  (e.g. 7 days after verification) would run as `AuditActor.system(...)` in a `@Transactional` job and needs that
  permission rule revisited.
* **`RESOLVED` does not count toward workload**, even though verification is pending: the technician's work is
  done. `PENDING_INFO` **does** count, because the technician still owns the incident.
* **SLA clocks are P4's.** Whether time in `PENDING_INFO` pauses the resolution timer is a P4 decision; the
  state machine only guarantees the state is distinguishable. `ACKNOWLEDGE` is the natural "response met" event.
* **Reasons** are trimmed; blank counts as absent; the maximum is 1000 characters (the audit `description`
  limit). Reasons are stored by the caller (audit `description`/`metadata`), never by the state machine.
* **Stateless.** The machine holds no incident. Concurrency (two people acting at once) is the service's job:
  lock or version the incident row, then apply. A lost race surfaces as 409 `CONCURRENT_MODIFICATION` or
  `INVALID_STATE_TRANSITION`, both already mapped by the global handler.
* **Not covered yet:** feedback (a post-`VERIFIED` rating), investigation notes, and the historical-resolution
  lookup from the P3 brief.
