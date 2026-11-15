# P1 handoff: Access & Administration

Status: functionally complete and verified; the deferred hardening of section 5 is **done** except the items marked open. This page is the single entry point for whoever takes P1 over for review, merge, deployment
or integration. It links to the detailed documents instead of repeating them.

## 1. What P1 delivers

| Area | Delivered | Detail |
|---|---|---|
| Authentication | Login (Bearer for scripts, HttpOnly `Secure` `SameSite=Strict` cookie for the web app), logout that revokes every token, malformed-`Origin` handling, CSRF header + exact Origin allow-list | [security.md](architecture/security.md) |
| Users and roles | EMPLOYEE / TECHNICIAN / ADMIN, admin user management with optimistic locking, first-admin command-mode bootstrap, registration OFF by default | [integration-guide.md](architecture/integration-guide.md) |
| Technician profiles | Skills, categories, workload ceiling, availability, escalation chain | integration-guide.md, "Technician profiles" |
| Catalogs | Incident categories and IT services (active/inactive lifecycle; the services page has a dependency map view) | integration-guide.md |
| SLA policies and assignment settings | Stored and served; timers, breach detection and escalation are P4 | integration-guide.md |
| Audit | Append-only audit trail (database trigger), admin retrieval API, incident trail gated by P2's `IncidentAccessPolicy` | [audit-retrieval.md](architecture/audit-retrieval.md) |
| Admin frontend | Connected to the real API | [admin-frontend-contract.md](architecture/admin-frontend-contract.md) |
| Shared contracts | SPI matrix, provider availability matrix, per-team handoff | [p1-contract-matrix.md](architecture/p1-contract-matrix.md) |
| Operations | HTTPS-only Kubernetes/Helm, build-time frontend API origin, separate liveness/readiness with a startup probe | section 4 |

## 2. How it was verified (B16 branch, 2026-11-12; the merged branch is in section 8)

| Check | Result |
|---|---|
| Backend: `./gradlew clean build`, JDK 17, `ITSM_REQUIRE_DOCKER=true`, PostgreSQL 16 | 446 tests, 0 failed, 0 skipped; every class listed in `backend.yml`'s required list ran (at the B16 commit; the suite on `main` is much larger, 750+ tests, all passing on 2026-11-14) |
| Frontend: typecheck, lint, production build | clean |
| Frontend fake-API Playwright suite | 206 passed |
| Real frontend/backend suite (`real-stack.yml`, PostgreSQL 16) | 64 of 64, checker: 0 skipped / 0 flaky / 0 failed, secret scan clean |
| GitHub run of the real-stack suite on `f8dac01` (run 43163150076) | success |

GitHub has **not** run the B16 commits: no workflow triggers on pushes to that branch (see section 6).

Re-run locally: backend `cd backend && ITSM_REQUIRE_DOCKER=true ./gradlew clean build`; fake suite `cd frontend && npm run test:e2e`;
real stack see [frontend/e2e-real/README.md](../frontend/e2e-real/README.md).

## 3. Running it

* **Local development:** `cd infra && docker compose up --build` (the `dev` profile, non-Secure cookie, registration on; plain HTTP on localhost only).
* **Kubernetes / Helm:** HTTPS only. Read "HTTPS and the public origin" in the [README](../README.md). The owner supplies the hostname, an existing
  `kubernetes.io/tls` Secret, `JWT_SECRET`, and builds the frontend image with `--build-arg NEXT_PUBLIC_API_URL=https://<host>`.
* **First administrator:** command-mode bootstrap (`--itsm.bootstrap-admin=true`, `ITSM_BOOTSTRAP_ADMIN_EMAIL/PASSWORD`). There is no default account.

## 4. Operational readiness: done (B16-A, B16-B)

* TLS and redirect on both ingresses; host and secret are values; no issuer or certificate is assumed; extra annotations allowed.
* The frontend image takes the public origin at build time. A runtime variable cannot change it. Verified in a real image build.
* Probes: liveness = process only; readiness = accepting traffic and PostgreSQL; startup budget 180 s. Redis and RabbitMQ do not affect either
  because no feature uses them. `/actuator/health` still reports them. A module that starts using one must add its indicator to the readiness group
  in `application.yml`. Probes and Prometheus use the separate management port 8081 (`security.md`, "Management port").

## 5. Hardening from the B16 assessment: status

Closed on 2026-11-14 unless marked open. Details and the reasoning are in [security.md](architecture/security.md).

| ID | Severity | What | Status |
|---|---|---|---|
| B16-05 | High | `TRUSTED_PROXIES` was the whole private address space and there was no NetworkPolicy | **Done.** No default any more: the Helm chart and `deploy.sh` require the ingress controller's pod CIDR, the raw manifest carries an invalid placeholder. NetworkPolicies limit the backend's API port to the ingress controller and PostgreSQL, Redis and RabbitMQ to the backend. **Operator still has to supply the real CIDR and use a CNI that enforces NetworkPolicy** (Kind's default does) |
| B16-06 | Medium | `security.md` claimed ingress-nginx appends `X-Forwarded-For`; by default it overwrites it | **Done** (doc corrected, with the `externalTrafficPolicy: Local` / proxy-protocol requirement and a way to verify). The cluster's actual topology still has to be checked by whoever deploys |
| B16-08 | Medium | Default passwords committed in the k8s secrets and Helm values | **Done** for Helm (`required`, no defaults; `deploy.sh` requires them) and the raw manifests (placeholders; Redis and RabbitMQ now read secrets). The `docker-compose.yml` development stack keeps its throwaway passwords on purpose |
| B16-03 | High | The application and Flyway shared one superuser that owns the audit table, so the append-only trigger could be disabled | **Done, opt-in.** A Flyway `afterMigrate` callback grants a runtime role DML only (audit: `SELECT, INSERT`); `RuntimeRoleGrantsIntegrationTest` proves it on PostgreSQL 16; `infra/docker-compose.roles.yml` wires it for compose. Off by default; **production must enable it and create the role** (the Helm chart does not provision database roles) |
| B16-07 | Medium | Prometheus could not scrape `/actuator/prometheus` (401) | **Done.** Actuator on port 8081, unpublished, open to Prometheus only by NetworkPolicy; `ManagementPortIntegrationTest` covers both ports. Added business metrics and a provisioned Grafana dashboard |
| B16-09 | Medium | Login throttle is per instance and keyed by full IPv6 address | **Accepted and documented** (`security.md`, "Other accepted limitations"): use an ingress-level limit or a shared counter if it matters |
| B16-10 | Low | `baseline-on-migrate: true` silently skipped V1 on a non-empty database | **Done.** Now `false` (`FLYWAY_BASELINE_ON_MIGRATE` to opt in) |
| B16-11 | Low | Local and production settings separated implicitly | **Documented** (`security.md`); no separate overlay file was added |

## 6. CI

* `backend.yml` (push to `main`, `feat/p1-admin-backend`; PR to `main`), `frontend.yml` (PR to `main`, path-filtered), `real-stack.yml` (push to `main`,
  `feat/p1-admin-backend`, `feat/p1-b15-real-stack-ci`; PR to `main`; `workflow_dispatch`, usable only once the file is on `main`).
* No workflow runs on a push to `feat/p1-b16-operational-readiness`. GitHub checks for it need a pull request to `main` (blocked while PR #4 conflicts)
  or an approved trigger change.
* Known flake: the fake-API suite failed intermittently under heavy machine load in earlier runs (`admin-catalog.spec.ts`, `admin-technicians.spec.ts`);
  it passed 206/206 here. Unfixed, outside P1's scope.

## 7. What the other teams must do

Details per team: [p1-contract-matrix.md](architecture/p1-contract-matrix.md) section 5 (written before P2 and P3 landed; re-read its "not implemented" rows
against this table).

| Team | Dependency | State on `main` (2026-11-14) |
|---|---|---|
| P2 | Register one `IncidentAccessPolicy` (the incident audit trail answers 503 without it) | **implemented** (`SpringIncidentAccessPolicy`) |
| P2 | Use `CategoryCatalog.requireActive` for new incidents | **done** (`IncidentIntakeService` calls `requireActive` for both category and service) |
| P3 | `AssignmentStrategyCatalog` | **implemented** (`RegisteredAssignmentStrategyCatalog`: `LOAD_BASED` (the key P1 seeds), `SKILL_BASED`, `ROUND_ROBIN`, `MANUAL`) |
| P3 | `TechnicianWorkloadProvider` | **implemented** (`StoreTechnicianWorkloadProvider`); P1 does not call it |
| P3 | Confirm the technician contract (`available` is an admin flag, not workload; `maxWorkload` is a ceiling; `escalationChain` nearest-first) and the `defaultMaxWorkload` cap of 100 | pending (a confirmation, not code) |
| P4 | SLA timers, breach detection, escalation, notifications, analytics | **built** (`sla/`, `escalation/`, `notification/`, `analytics/`); `unassignedEscalationMinutes` is owned by P4 (`escalation_settings`, `V41`) |
| All | Audit: publish through `AuditPublisher` inside the caller's transaction (`Propagation.MANDATORY`) | P1 provides it; P2 to P4 publish through it |
| P2 | Incident AI features (analysis, chatbot) send the incident text to the Google Gemini API when `gemini.api-key` is set | **open decision:** confirm this is acceptable for the deployment's data policy, and check Google's terms for the key's tier (free-tier terms differ from paid ones on how prompts may be used). Off when no key is set |

## 8. Integration: `feat/p1-b18-final-integration` (P1 + `main` + P3's PR #10), merged to `main` as PR #11

PR #4 (P1) conflicted with `main`. The integration branch merges `main` and P3's PR #10 (`feat/p3-persistence-stores`) into P1, keeping both
sides in every conflict (docs, `frontend/Dockerfile`, P1's stricter `ApplicationStartupIntegrationTest`, P1's `PageQuery` fix with the B13 overflow
guard). No migration collision: V1, V10 to V16 (P1), V20 (P3's incidents table), V30 to V32 (P3).

P1's side of the merge: the assignment-settings tests run against P3's real `RegisteredAssignmentStrategyCatalog` (no fake), the V16 migration test pins
Flyway to target 16, the real-stack assignment spec asserts the real catalog and the seeded `LOAD_BASED`, and the workflows also trigger on this branch.
P3's side (their fixture role `USER` to `EMPLOYEE`, the incident workspace's own mock people, the live client going through `lib/api.ts`) came in with PR #10.

Verified locally on the merged branch (2026-11-12): backend 606 tests, 0 failed, 0 skipped; frontend typecheck, lint and build clean; real-stack suite
64 of 64 (checker OK, secret scan clean); fake suite passes (the 3 skips are P3's opt-in live incidents spec and an existing live-mode throttle skip;
one run under heavy machine load timed out in 4 incident tests that pass when run alone).
