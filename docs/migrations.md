# Flyway migration plan

Database: PostgreSQL 16. Flyway 10.10.0 (the version Spring Boot 3.3.1 manages).

## 1. Version blocks

Each person writes only inside their block, so two people can never create the same version.

| Block | Owner | Content |
|---|---|---|
| `V1` | boilerplate | `users` (existing, do not edit) |
| `V10-V19` | P1 | users evolution, categories, services, technician profiles, SLA policies, assignment settings, audit |
| `V20-V29` | P2 | incident intake |
| `V30-V39` | P3 | lifecycle, assignment, investigation, feedback |
| `V40-V49` | P4 | SLA tracking, escalation, notifications, analytics |

Rules
1. Name files `V<n>__<owner>_<description>.sql` (e.g. `V21__p2_incidents.sql`). Never edit a merged migration; add a new one.
2. **A foreign key may only point at a table in the same or a lower block.** This is what makes a
   from-scratch run succeed in version order: P1's tables always exist before P2-P4 reference them.
3. Need more than ten versions? Agree a new block, then update `MigrationVersioningTest`.
4. `MigrationVersioningTest` (runs in `./gradlew test`) fails the build on a duplicate version, a
   badly named file, or a version outside the reserved blocks. It cannot check SQL or FK order, so also
   run the clean-database check in section 4.

## 2. `spring.flyway.out-of-order`: decision

**Not enabled** (Flyway's default is `false`; this branch does not add the setting). Verified behaviour
(Flyway 10.10.0, PostgreSQL 16), with P2's `V20` already applied and P1's `V10` merged afterwards:

| Setup | Result |
|---|---|
| Fresh database, default Flyway | applies `V1, V10, V20` in order: **works** |
| Database that already has `V20`, default Flyway | **fails loudly**: `Validate failed ... Detected resolved migration not applied to database: 10` |
| Same database with `-outOfOrder=true` | applies `V10` after `V20`, with a warning: `Migration of schema may not be reproducible` |

Why it stays off: with it on globally, every environment can end up with a *different* application order,
a later-merged lower migration silently runs against a schema it was never tested against, and a fresh
database (what CI and new teammates get) no longer matches your long-lived one. The failure above is
the desirable behaviour: it tells you the merge order is wrong *before* the drift happens.

What to do when you hit the failure
* **Local/dev database:** reset it (`docker compose down -v`, then up). It is disposable.
* **Shared/demo database:** merge in block order where possible. If it truly happened, apply the late
  migration once, deliberately, with the CLI (`flyway -outOfOrder=true migrate`), then verify a clean
  database still migrates. Do not turn the flag on in `application.yml`.

If the team later decides to enable it anyway, document the owner, and keep the section 4 check as a
required CI gate so a clean build remains the source of truth.

Note: `application.yml` has `baseline-on-migrate: true`. On a *non-empty* schema with no Flyway history
table Flyway baselines at version 1 and skips `V1`. Harmless on a fresh database; be aware when pointing
the app at an existing one.

## 3. P1 migrations

**Merged into `src/main/resources/db/migration`:** `V10__p1_audit_events.sql` (the former draft `V16`). Audit goes first
because it has no foreign keys and every later P1 change publishes audit events. `V10` stores the actor by id/role/job
name (no email) and adds a stable `event_id`, an actor-shape CHECK and the append-only triggers.

**Merged:** `V11__p1_incident_categories.sql` and `V12__p1_it_services.sql` (replacing the old drafts of the same
numbers). Differences from those drafts:

* `V11` keeps keywords in a child table `incident_category_keywords` (not a `TEXT[]` column) so PostgreSQL can enforce
  keyword rules: primary key `(category_id, keyword)` = no duplicate within a category; a CHECK = stored normalised
  (trimmed, lower-case, single spaces, 2-30 chars of `[a-z0-9 '&./+-]`); a partial unique index on `keyword WHERE
  category_active` = no keyword shared by two active categories. `category_active` mirrors the owner's `active` through
  an insert trigger and a composite FK `(category_id, category_active) -> incident_categories (id, active) ON UPDATE
  CASCADE`, so deactivating a category frees its keywords and reactivating one that collides fails, even under races.
  Also: case-insensitive unique `lower(name)`, unique immutable `code`, `version` (optimistic locking),
  `created_at`/`updated_at`, and the 9 specification categories as seed data.
* `V12` adds case-insensitive unique `lower(name)`, `version`, timestamps, normalisation CHECKs, an index on
  `lower(owner_team)`, and a trigger on `service_dependencies` that rejects cycles (recursive walk under
  `pg_advisory_xact_lock(7310002)`, the same key the application takes; SQLSTATE 23000 -> 409). Self-dependency (CHECK)
  and duplicate edges (primary key) were already in the draft. Edges are direct only.
* Neither table is ever deleted from; retiring a row means `active = FALSE`, so incident references stay valid.

**Merged:** `V13__p1_sla_policies.sql` and `V14__p1_assignment_settings.sql` (replacing the old drafts `V14`/`V15`).

* `V13 sla_policies`: nullable `service_id` (NULL = global policy for that priority), `priority` CHECK `P1..P4`,
  `response_minutes > 0`, `resolution_minutes > response_minutes`, `resolution_minutes <= 43200` (30 days),
  `warning_threshold_pct` 1-99 (default 80, required by `SlaPolicyView`), `active`, `version`, timestamps. Seeds the four
  specification globals (5/30, 15/120, 60/480, 240/1440 minutes); nothing depends on them existing.
  **Uniqueness uses two partial unique indexes, not `NULLS NOT DISTINCT`:** one active policy per `(service_id, priority)`
  and one active global policy per `priority`. `UNIQUE NULLS NOT DISTINCT (service_id, priority)` (the old draft)
  treats a NULL service as one value and covers inactive rows too, so it would forbid keeping an inactive policy next to its
  replacement and destroy history. With the partial indexes a service-specific and a global policy for the same priority
  coexist, and any number of inactive rows are kept.
* `V14 assignment_settings` / `assignment_category_overrides`: a singleton row (`id = 1` by CHECK; a trigger forbids
  DELETE and TRUNCATE) with `active_strategy` (UPPER_SNAKE_CASE CHECK), `default_max_workload` 1-100, `updated_by` (user id
  only), `version`, `updated_at`; overrides are one row per category (`category_id` is the primary key and a foreign key to
  `incident_categories`). Strategy keys are **not** validated in SQL: the set of strategies is P3's, so the application
  checks them against P3's `AssignmentStrategyCatalog`. The seed `LOAD_BASED`/5 is the default from the earlier draft and
  the integration guide; it is **not verified** against P3 (see "Assignment settings" in the integration guide).

**`V10`-`V15` are merged; the one remaining draft already carries its final number (`V16`).** The planned P1
sequence is the order the branches merge, so that version order equals merge order (Flyway `out-of-order` stays off):

| Version | Migration | Status |
|---|---|---|
| `V10` | audit events | merged |
| `V11` | incident categories | merged |
| `V12` | IT services | merged |
| `V13` | SLA policies | merged |
| `V14` | assignment settings | merged |
| `V15` | user extensions (`active`, `token_version`, `version`, `updated_at`, canonical roles, unique lower-case email) | merged on `feat/p1-admin-backend` (B5) |
| `V16` | technician profiles (B6) | draft, file already renamed `V16__...`; moves into `src/main/resources` with B6 |
| `V17`+ | refresh tokens (future, needs frontend coordination) | not started; must not reuse `V16` |

Drafts live outside `src/main/resources` and are applied only when their branch moves them across. Within P1's block `V10-V19`, `V18` and `V19` stay free.

### Remaining drafts

Located in [`docs/migrations/drafts/`](migrations/drafts/) and **not** in `src/main/resources`, so nothing
is applied until the matching P1 branch moves its file across. They are the *validated* versions of the
earlier proposal: the first draft had invalid syntax (`CREATE INDEX ON t (a), (b);` is a PostgreSQL syntax
error: one `CREATE INDEX` per statement) and a foreign key to a table created later.

| File | Creates |
|---|---|
| `V16__p1_technician_profiles.sql` | `technician_profiles`, `technician_skills` (B6; renamed from `V13`) |

### V15 (users) — what it does and what it deliberately leaves out

* Aborts (RAISE EXCEPTION, whole migration rolls back) if two users share an email differing only by case/whitespace,
  or if a role is not `EMPLOYEE`/`TECHNICIAN`/`ADMIN`/legacy `USER` (after trim + upper-case). Nothing is merged or deleted:
  resolve the data by hand and re-run. Covered by `UsersMigrationIntegrationTest`.
* Maps `USER` -> `EMPLOYEE`, trims/lower-cases emails, adds `active` (default true), `token_version` (0), `version` (0,
  optimistic lock) and `updated_at`; CHECKs for role, normalised email and `token_version >= 0`; unique index on `lower(email)`.
* Default role is now `EMPLOYEE`. Existing accounts stay active. JWTs issued before this release (subject = email, no
  version claim) are rejected, so every user signs in once more after the deploy.
* Dropped from the old draft: `failed_logins`, `locked_until`, `department`, `phone` (no lockout feature or consumer yet).
* The old `docs/migrations/drafts/V10__p1_extend_users.sql` was removed; The B6 draft was renamed `V13__...` -> `V16__...`
  during the B5 review.

Design notes
* Audit rows carry no foreign keys on purpose (they must outlive users and incident rows). Append-only
  is enforced by the trigger, plus `@Immutable` and a save-only repository in code.
* `users.created_at` from `V1` is `TIMESTAMP` (no zone) while the new tables use `TIMESTAMPTZ`. Left as is
  to avoid touching the baseline; revisit in auth hardening.
* "This user is a TECHNICIAN" for `technician_profiles` is checked in the application, not in SQL.

## 4. Validating the complete sequence from an empty PostgreSQL 16

This is what was run to validate the original drafts (all eight migrations applied; a second run was a no-op).
CI now runs the main-location part of it on every PR (`Backend / Flyway on a clean PostgreSQL 16`, twice, plus `validate`),
and the Spring startup test boots on a clean PostgreSQL 16 with `ddl-auto: validate`.

> **The `/m/drafts` mount below no longer works as written:** the remaining drafts still carry the pre-renumbering
> numbers (`V10`, `V13`): `V10` collides with the merged audit migration and neither matches the planned
> sequence. Validate a draft only after
> renumbering it as described in section 3.

```bash
docker network create itsm-val
docker run -d --name itsm-pg16 --network itsm-val -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=itsm postgres:16

docker run --rm --network itsm-val \
  -v "$PWD/backend/src/main/resources/db/migration":/m/main:ro \
  -v "$PWD/docs/migrations/drafts":/m/drafts:ro \
  flyway/flyway:10.10.0 -url=jdbc:postgresql://itsm-pg16:5432/itsm -user=postgres -password=pw \
  -locations=filesystem:/m/main,filesystem:/m/drafts -validateMigrationNaming=true migrate

docker rm -f itsm-pg16 && docker network rm itsm-val
```

Once drafts are moved into `src/main/resources/db/migration`, drop the `/m/drafts` mount and location.
Behaviour was also probed on that database: `UPDATE`/`DELETE`/`TRUNCATE` on `audit_events` are rejected
while `INSERT` works; a second global `P1` SLA row, `resolution < response`, a service depending on
itself, an invalid role, a second `assignment_settings` row and a technician reporting to themselves are
all rejected.
