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
