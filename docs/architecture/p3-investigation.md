# P3: investigation timeline

Code: `backend/src/main/kotlin/com/itsm/investigation/`. Pure domain logic like the [state machine](p3-lifecycle.md)
and the [assignment engine](p3-assignment.md): rules and a storage contract, no database yet.

The timeline is the readable story of an incident: what the technician tried and found, the questions asked of the
reporter and the answers, how it was fixed. It is **not** the audit trail. Audit records *that* something changed
(who, when, from/to state) for accountability; the timeline records *what was said and done* for the people working
the incident. Both are written in the same transaction, and the timeline never replaces an audit event.

## Entry types

| Type | Added by | How it is created | Default visibility | Allowed |
|---|---|---|---|---|
| `NOTE` | technician, admin | directly | internal | `ACKNOWLEDGED`, `IN_PROGRESS`, `PENDING_INFO`, `RESOLVED` |
| `FINDING` | technician, admin | directly | internal | same |
| `ACTION_TAKEN` | technician, admin | directly | internal | same |
| `INFO_REQUEST` | technician | transition `REQUEST_INFO` | reporter-visible | (by the transition) |
| `INFO_RESPONSE` | reporter | transition `PROVIDE_INFO` | reporter-visible | (by the transition) |
| `RESOLUTION` | technician | transition `RESOLVE` | reporter-visible | (by the transition) |
| `REOPEN_REASON` | reporter | transition `REOPEN` | reporter-visible | (by the transition) |
| `REASSIGNMENT` | admin | transition `REASSIGN` | **internal, always** | (by the transition) |

Transitions with no narrative (assign, acknowledge, start work, verify, close, classify) add no entry; the audit
trail already holds them. Every transition that *requires* a reason produces an entry (tested), so no explanation
is ever dropped.

## Intricacies

* **Conversation entries can only come from transitions.** `InvestigationPolicy.newManualEntry` rejects them with
  400. This keeps the timeline and the state consistent: an `INFO_REQUEST` cannot exist unless the incident really
  went to `PENDING_INFO`, and the reporter's answer cannot exist unless it really resumed work.
* **`PROVIDE_INFO` requires text** (changed in this branch; it used to be optional). The answer *is* the transition,
  and an empty answer would resume work without telling the technician anything.
* **Manual notes are allowed in `RESOLVED`.** The technician can document the fix while it awaits the reporter's
  verification. They are not allowed in `VERIFIED` or `CLOSED`: a confirmed incident's history is final. Before
  `ACKNOWLEDGED` there is nothing to investigate (`ASSIGNED` means the technician has not accepted the incident).
* **Visibility is a rule, not a convention.** `INTERNAL` entries are hidden from anyone without `INCIDENT_WORK`
  (`InvestigationPolicy.canSee` / `visibleTo`). Two invariants are enforced in the entry itself, so they cannot be
  broken by a caller: a question or answer in the conversation can never be internal (the reporter would be waiting
  on something they cannot see), and a `REASSIGNMENT` note can never be reporter-visible (it may discuss colleagues).
  A technician may choose to share a `NOTE`/`FINDING`/`ACTION_TAKEN` with the reporter.
* **Edge case: a technician who reports an incident** holds `INCIDENT_WORK`, so `canSee` shows them the internal
  notes of their own incident. Whether they may open it at all is `IncidentAccessPolicy` (P2). Decide whether that
  is acceptable; if not, the rule needs the reporter id, as `FeedbackPolicy` takes.
* **Append-only.** `InvestigationLog` has `append` and `list`, no update, no delete. A mistake is corrected with a
  new entry. The database should enforce it too (trigger, as for `audit_events`). There is no moderation
  feature: removing a note that contains something it should not (a pasted password, say) is a data-protection
  action for an administrator with database access, outside the application.
* **Every entry records the incident state when written** (`incidentState`), so the timeline reads correctly
  after the incident moves on ("noted while `PENDING_INFO`").
* **Order of checks** for a manual entry: shape (type, body) → state → permission. Same philosophy as the state
  machine: a caller acting on an incident that has moved on learns that before learning anything else.
* **Body:** trimmed, 1 to 4000 characters. Errors carry a `body` field error for the form.
* **The text never leaves the timeline.** The audit action is `INCIDENT_INVESTIGATION_ENTRY_ADDED` with metadata
  `entryType` and `visibility` only; the body is never copied into audit metadata or logs, because it can contain
  personal data. (The audit metadata rules also forbid long free text.) Note that `RESOLVE`'s reason is also the
  `RESOLUTION` entry; the audit `description` should be a short fixed phrase, not the reason.
* **Ownership is the service's check.** The policy checks permission, not "is this *your* incident". The service
  must confirm the author is the assigned technician (manual notes, `INFO_REQUEST`, `RESOLUTION`) or the reporter
  (`INFO_RESPONSE`, `REOPEN_REASON`) via `IncidentAccessPolicy`; admins are exempt, as for transitions.
* **Not authored by the system.** Every entry has a human `authorId`. Automated events (SLA warnings, escalations)
  are audit events, not timeline entries.

## Using it

```kotlin
@Transactional
fun resolve(incidentId: String, summary: String) {
    val me = currentUser.current()
    val incident = incidents.require(incidentId)
    val result = IncidentStateMachine.apply(incident.state, IncidentTransition.RESOLVE, me, summary)
    incident.state = result.newState
    InvestigationPolicy.entryFor(incidentId, result, me, clock.instant())?.let(log::append)
    audit.publish(/* INCIDENT_RESOLVED, previousState/newState, short fixed description, no summary text */)
}

fun timeline(incidentId: String): List<InvestigationEntry> =
    InvestigationPolicy.visibleTo(currentUser.current(), log.list(incidentId))
```

## What is not built

* The `InvestigationLog` implementation and its table (needs the incidents schema from P2; block `V30-V39`).
* REST endpoints and the technician/reporter UI for the timeline.
* **Historical resolutions for active tickets** (the P3 brief): "show how similar past incidents were fixed".
  `RESOLUTION` entries are the source, so they are stored and reporter-visible by design, but the query needs
  incidents with categories and a matching rule (same category and service first; keyword similarity later).
* Attachments, mentions, edit history.

## Open questions

1. Should a technician-reporter see internal notes on their own incident? (above)
2. Should the reporter be able to add a free comment outside a request for information? Today only answering a
   request is possible, which keeps the reporter's input structured but prevents "any update?" messages.
3. Do admins need to add reporter-visible notes on someone else's behalf? Allowed today (they hold the permission).
