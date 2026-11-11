# P3: technician assignment engine

Code: `backend/src/main/kotlin/com/itsm/assignment/`. Pure domain logic like the [state machine](p3-lifecycle.md):
no database, no repositories. It decides **who** should get an incident; applying the decision (changing the
incident, publishing audit) is the job of the assignment service, which is not written yet (see
[What is not built](#what-is-not-built)).

## Pipeline

```
candidates (profile + workload + last assignment)
  -> EligibilityPolicy        available? under limit? not excluded?
  -> strategy for the category (override beats global)   -> rank -> first wins
  -> TechnicianRecommender    always: scored, explained top 5
  => AssignmentDecision (outcome, strategyKey, chosen, recommendations, fellBackFrom)
```

`AssignmentEngine.decide(settings, request, candidates)` is the one entry point. It never throws for "nobody
suitable"; that is an outcome (below). Inputs are values (`AssignmentRequest`, `Candidate`), so it needs neither
P2's incident entity nor P1's beans, and every rule is unit-tested without Spring or a database.

## Eligibility (`EligibilityPolicy`)

A technician is eligible when **all** hold: marked available; not in `request.excludeTechnicianIds`
(the current assignee when reassigning); `activeCount < effective max`.

* **Effective max** = the technician's own `maxWorkload` if it is positive, otherwise the system
  `defaultMaxWorkload`. **Assumption:** a non-positive own limit means "not set". The admin form does not define
  0, so confirm with P1. If the default is also not positive the technician has no capacity (fail closed).
* Capacity is strictly below the limit: 5 of 5 is full.
* **Skill is not part of eligibility.** Only the skill-based strategy requires it. This keeps round robin and
  load-based usable when nobody has the skill, and lets an admin assign outside listed skills.
* Manual assignment of a named technician uses `requireCanTake`, which **validates** instead of filtering and
  throws `TechnicianUnavailable` (409) or `WorkloadExceeded` (409, message "Technician 7 is at 5 of 5 allowed
  active incidents"). Skill is deliberately not checked there.
* The caller should still pass technicians from `UserDirectory.listAvailableTechnicians(categoryId)`; the
  engine re-checks `available` itself because a snapshot can be stale by the time it is used.

## Strategies

Keys are `UPPER_SNAKE_CASE` and are what the admin stores. A new strategy is a new `@Component` implementing
`AssignmentStrategy`; nothing else changes (tested).

| Key | Auto-assigns | Order (best first) | Tie-breaks |
|---|---|---|---|
| `LOAD_BASED` | yes | lowest utilisation `active / max` | fewer absolute incidents, more experience, lowest id |
| `SKILL_BASED` | yes | highest proficiency in the incident's category | lower utilisation, more experience, lowest id |
| `ROUND_ROBIN` | yes | never-assigned first, then longest since last assignment | lowest id |
| `MANUAL` | **no** | load order, for display only | lowest id |

Intricacies:

* **`LOAD_BASED` is mandatory.** P1's migration `V15` seeds it as the default, so `AssignmentEngine` refuses to
  construct (the application will not start) if it is not registered. It is also the fallback (below).
* **Utilisation is compared with integer cross-multiplication** (`a*d` vs `c*b`), not floats, so ordering is
  exact. 1 of 10 beats 1 of 2 although both have one incident.
* **`SKILL_BASED` drops unskilled technicians** instead of ranking them last. If nobody is skilled the outcome
  is `NO_ELIGIBLE_TECHNICIAN`: an unqualified person is never picked silently. Recommendations are still returned
  so an admin can act. With no category, skill does not apply and the order is load-based. Minimum proficiency
  is 1 (any recorded skill counts).
* **`ROUND_ROBIN` is stateless.** It derives the rotation from `Candidate.lastAssignedAt` (to be read from the
  database), not an in-memory pointer, so it survives restarts and works with several backend instances. An
  unavailable or full technician is skipped without being "owed" a turn. It ignores skill and load by design.
* **`MANUAL` exists for compatibility.** The admin UI offers `MANUAL`, `ROUND_ROBIN` and `SKILL_BASED` as
  placeholder names (see `frontend/src/features/admin/README.md`: "not a P3 contract"). Registering `MANUAL`
  means a setting the UI can produce is also valid on the backend. The frontend will need `LOAD_BASED` added.
* **Determinism.** Every comparator ends in the technician id, and the test suite asserts that the result does not
  depend on input order. Required of any new strategy (documented on the interface).

### Outcomes

| Outcome | When | What the caller does |
|---|---|---|
| `ASSIGNED` | an auto strategy ranked at least one technician | assign `chosen`; publish `INCIDENT_ASSIGNED` with `strategy` |
| `MANUAL_REQUIRED` | the effective strategy is `MANUAL` and someone is eligible | leave `CLASSIFIED`; show `recommendations` to an admin |
| `NO_ELIGIBLE_TECHNICIAN` | nobody left after eligibility (or after the skill filter) | leave `CLASSIFIED`; this is P4's escalation signal |

### Unknown or stale strategy key

If `settings` names a key that is not registered (hand-edited row, a strategy removed in a later release), the
engine falls back to `LOAD_BASED`, logs a warning, and sets `fellBackFrom` to the configured key. It does not
fail, because a configuration slip must not stop incidents being assigned. Record `fellBackFrom` in the audit
metadata so the fallback is visible. `RegisteredAssignmentStrategyCatalog` (the `AssignmentStrategyCatalog` SPI)
reports exactly the registered keys, so a key that passes P1's validation is never replaced.

## Recommendations (`TechnicianRecommender`)

Always computed from the eligible set, independent of the configured strategy: the strategy decides who is
auto-assigned, the recommender explains who looks best.

`score = 100 x (wSkill x skill + wHeadroom x headroom + wExperience x experience)`, rounded to one decimal.

* `skill` = proficiency / 5 in the incident's category (0 when unrecorded; **0.5, neutral, with no category**)
* `headroom` = `1 - active / effective max`
* `experience` = `min(years, 10) / 10` (capped, so 40 years scores the same as 10)

| Priority | skill | headroom | experience |
|---|---|---|---|
| P1, P2 | 0.45 | 0.20 | 0.35 |
| P3, P4 | 0.40 | 0.45 | 0.15 |

Reasoning: a critical incident should go to someone experienced even if busier; a routine one where there is
room. **These weights are a starting point, not a specification** (the project spec does not define them);
they live in `weightsFor` and are tested, so they can be tuned. Ties: higher skill, then lowest id. Each
recommendation carries plain-language reasons ("Skill 4 of 5 in this category", "2 of 5 active incidents",
"1 year of experience"), safe to show to users. Scores are only comparable within one request.

## Contracts with other modules

| With | Contract |
|---|---|
| P1 | `AssignmentStrategyCatalog` is **implemented here** (`registeredKeys()`); P1 validates the admin's key against it. `AssignmentSettingsView` (`strategyFor`, `defaultMaxWorkload`) is the engine's input. |
| P1 | `TechnicianSummary` supplies skills (`categoryId -> 1..5`), `maxWorkload`, `experienceYears`, `available`. |
| P2 | Will supply incident data (category, priority) and the workload/last-assignment counts. `IncidentAccessPolicy` is theirs. |
| P4 | `NO_ELIGIBLE_TECHNICIAN` and `MANUAL_REQUIRED` leave an incident `CLASSIFIED`, so SLA timers and escalation must handle an unassigned classified incident. |

## What is not built

Deliberately, because each needs data or beans that do not exist yet. Do not add main-code dependencies on SPIs
whose providers have not merged: the application would stop starting (see the integration guide).

* **`TechnicianWorkloadProvider` implementation.** It counts incidents in states with
  `countsTowardWorkload`, which needs the incidents table (P2, `V20-V29`). Until then there is no bean for P1 or
  P4 to inject.
* **The assignment service** that builds `Candidate`s from `UserDirectory` + workload + last-assigned data, calls
  `decide`, changes state through `IncidentStateMachine` and publishes `INCIDENT_ASSIGNED` in one
  `@Transactional` method.
* **Persistence of `lastAssignedAt`.** Derived from the assignment history; needs a P3 table in `V30-V39` or an
  index on the audit/assignment data.
* **REST endpoints and the P3 UI.**

## Open questions

1. Is a non-positive technician `maxWorkload` really "use the default"? (P1)
2. Should `MANUAL` stay, and should the admin UI add `LOAD_BASED`? (P1, frontend)
3. Are the recommendation weights acceptable, or does the course spec define a formula? (team)
4. **The admin UI has a `fallbackStrategy`** ("tried when the selected one finds nobody", seeded as
   `ROUND_ROBIN`), but `AssignmentSettingsView` in `common/spi` has no such field, so the backend cannot read it
   and the engine does not model it. Either P1 adds it to the view (SPI changes are additive: add a field with a
   default) and the engine retries with it when the primary strategy finds nobody, or the UI field is dropped.
   Until then `NO_ELIGIBLE_TECHNICIAN` is final. Note the interaction with `SKILL_BASED` returning nobody when no
   one is skilled: a fallback to `ROUND_ROBIN` would be exactly the case it is meant for.
5. Should an incident left `CLASSIFIED` by `NO_ELIGIBLE_TECHNICIAN` retry automatically when a technician frees up? (P4)
