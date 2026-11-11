# P3: post-resolution feedback

Code: `backend/src/main/kotlin/com/itsm/feedback/`. Pure domain logic and a storage contract, like the rest of P3.

## Flow

```
RESOLVED --VERIFY--> VERIFIED --(feedback, optional)--> CLOSED
    |
    +--REOPEN (reporter: "not fixed", reason required)--> IN_PROGRESS
```

Verifying and rating are **two separate acts**. `VERIFY` says "it is fixed"; the rating says how the handling went.
A reporter who is unhappy with the *fix* does not leave a low rating, they `REOPEN` at `RESOLVED`. Feedback is
about the service, after the fix is accepted. Closing does not wait for it.

## Rules (`FeedbackPolicy.submit`)

| Rule | Detail |
|---|---|
| Who | Only the incident's **reporter**. Not another employee, not the technician who handled it, and **not an admin**: feedback is the reporter's own voice and an admin submitting it would falsify it. Enforced in the pure policy (it receives `reporterId`), unlike the transition permissions where ownership is left to the service. |
| When | The incident is `VERIFIED` or `CLOSED`. It stays open after closing so a reporter can reply later. Earlier states give 409 `CONFLICT` ("Feedback can only be given once the fix has been confirmed"). |
| How many | One per incident, final. A second attempt is 409 `DUPLICATE_RESOURCE`. There is no edit. |
| Rating | Integer 1 to 5. Field error `rating`. |
| Comment | Optional, trimmed, blank means none, at most 1000 characters. Field error `comment`. |

Order of checks: **who → state → duplicate → input.** Authorisation first, so a stranger learns nothing about
an incident (not even its state); input last, so a request is not bounced for a typo before the state and
duplicate questions are settled. All invalid fields are reported together in one 400.

## Intricacies

* **Races.** The duplicate check sees only feedback that is already committed. Two submissions in separate
  transactions can both pass it. `FeedbackStore` therefore requires a **unique constraint on the incident id**;
  the global handler already maps a unique violation to 409 `DUPLICATE_RESOURCE`, so the loser gets the same
  answer. The check exists to give the common case a clean message, the constraint to be correct.
* **Audit.** Publish `INCIDENT_FEEDBACK_RECORDED` in the same `@Transactional` method as the save, with metadata
  `rating` only. The comment is never put in audit metadata or logs (it may name people).
* **Feedback is not a state.** It does not move the incident, so it is not a lifecycle transition and does not
  appear in `IncidentTransition`. It is a record attached to an incident that is already `VERIFIED` or `CLOSED`.
* **Analytics are P4's.** Ratings are stored per incident and technician-agnostic; averaging per technician or
  category belongs to analytics. The technician is found through the incident, not stored on the feedback, so a
  reassignment cannot leave feedback pointing at the wrong person.

## Known gap in `common` (needs P1)

`RolePermissions` gives `INCIDENT_VERIFY_OWN` to `EMPLOYEE` (and `ADMIN`, which holds everything) but **not to
`TECHNICIAN`**, while technicians can create incidents. A technician who reports their own incident can therefore
neither `VERIFY` nor `REOPEN` it nor rate it: it sits in `RESOLVED` until an admin verifies it on their behalf.
Granting `INCIDENT_VERIFY_OWN` to `TECHNICIAN` would fix it. It is P1's file, so it is not changed here; a test
(`known gap - a technician who reported the incident cannot rate it yet`) pins today's behaviour and should be
flipped when the permission is granted.

## What is not built

The `FeedbackStore` implementation and table, the REST endpoint (`POST /api/incidents/{id}/feedback`, naming
subject to P2's conventions), the UI prompt shown after verification, and any reminder to leave feedback.

## Open questions

1. Should a low rating (1 or 2) require a comment? Not enforced; it would help admins but adds friction.
2. Should feedback be possible only for a limited time after closing (for example 14 days)?
3. Should a reporter be able to edit their feedback once, within a short window?
