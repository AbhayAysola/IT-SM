# P1 handoff: Access & Administration

Status: functionally complete and verified. This page is the single entry point for whoever takes P1 over for review, merge, deployment
or integration. It links to the detailed documents instead of repeating them.

## 1. What P1 delivers

| Area | Delivered | Detail |
|---|---|---|
| Authentication | Login (Bearer for scripts, HttpOnly `Secure` `SameSite=Strict` cookie for the web app), logout that revokes every token, malformed-`Origin` handling, CSRF header + exact Origin allow-list | [security.md](architecture/security.md) |
| Users and roles | EMPLOYEE / TECHNICIAN / ADMIN, admin user management with optimistic locking, first-admin command-mode bootstrap, registration OFF by default | [integration-guide.md](architecture/integration-guide.md) |
| Technician profiles | Skills, categories, workload ceiling, availability, escalation chain | integration-guide.md, "Technician profiles" |
| Catalogs | Incident categories and IT services (active/inactive lifecycle) | integration-guide.md |
| SLA policies and assignment settings | Stored and served; timers, breach detection and escalation are P4 | integration-guide.md |
| Audit | Append-only audit trail (database trigger), admin retrieval API, incident trail gated by P2's `IncidentAccessPolicy` | [audit-retrieval.md](architecture/audit-retrieval.md) |
| Admin frontend | Connected to the real API | [admin-frontend-contract.md](architecture/admin-frontend-contract.md) |
| Shared contracts | SPI matrix, provider availability matrix, per-team handoff | [p1-contract-matrix.md](architecture/p1-contract-matrix.md) |
| Operations | HTTPS-only Kubernetes/Helm, build-time frontend API origin, separate liveness/readiness with a startup probe | section 4 |

## 2. How it was verified (B16 branch, 2026-11-12; the merged branch is in section 8)

| Check | Result |
|---|---|
| Backend: `./gradlew clean build`, JDK 17, `ITSM_REQUIRE_DOCKER=true`, PostgreSQL 16 | 446 tests, 0 failed, 0 skipped; every class listed in `backend.yml`'s required list ran |
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
  because P1 does not use them. `/actuator/health` still reports them. A module that starts using one must add its indicator to the readiness group
  in `application.yml`.

## 5. Deferred hardening: assigned to the deployment/operations owners

These are findings from the B16 assessment. None changes application behaviour; each needs values or decisions only the owner has. None was
started. **Production deployment is blocked until C and D are done** (the MVP on a closed network is not).

| ID | Severity | What | Owner | Needs from the owner |
|---|---|---|---|---|
| B16-05 | High | `TRUSTED_PROXIES` is the whole private address space and there is no NetworkPolicy. Any pod can forge `X-Forwarded-For` and bypass the per-email login limit (demonstrated: 12 guesses never throttled). Narrow it to the ingress controller's pod CIDR and add a NetworkPolicy (ingress to backend; backend to Postgres/Redis/RabbitMQ) | Platform | ingress type/version, its pod CIDR or namespace labels, cluster CIDRs, hop count, whether a cloud load balancer or CDN is in front |
| B16-06 | Medium | `security.md` says ingress-nginx appends `X-Forwarded-For` by default; its default is to overwrite with the connecting address. Behind a load balancer with `externalTrafficPolicy: Cluster` every user would share one login bucket. Confirm the topology and correct the doc | Platform | as above |
| B16-08 | Medium | Default passwords (`itsm_password`, `redis_password`, `rabbitmq_password`) are committed in the k8s secrets and Helm values. Make them required like `jwt.secret` | Platform | how secrets are provisioned |
| B16-03 | High | The application and Flyway share one PostgreSQL superuser that owns the audit table, so the append-only trigger can be disabled by the application's own account (demonstrated). Use a migration-owner role and a runtime role with only `SELECT, INSERT` on `audit_events`; apply the grants in an `afterMigrate` step, not in a migration. The privilege model was proven on PostgreSQL 16 | Database / Platform | who owns the migration role, how credentials are issued |
| B16-07 | Medium | Prometheus cannot scrape `/actuator/prometheus` (401). A separate management port does not help by itself: the security chain applies there too, so it needs a small `SecurityConfig` rule limited to the management port, plus a NetworkPolicy | Backend + Platform | whether Prometheus runs in the cluster, its namespace and labels |
| B16-09 | Medium | Login throttle is per instance (2 replicas allow about 2x), resets on restart, and keys IPv6 by full address (a /64 can rotate addresses). Accepted for the MVP with an ingress-level limit; no Redis needed | Platform | whether IPv6 clients reach the ingress |
| B16-10 | Low | `spring.flyway.baseline-on-migrate: true` silently skips V1 on a non-empty database without a history table | Backend | decide for production |
| B16-11 | Low | Local and production settings are separated implicitly (profiles/values), not by a documented overlay | Platform | none |

## 6. CI

* `backend.yml` (push to `main`, `feat/p1-admin-backend`; PR to `main`), `frontend.yml` (PR to `main`, path-filtered), `real-stack.yml` (push to `main`,
  `feat/p1-admin-backend`, `feat/p1-b15-real-stack-ci`; PR to `main`; `workflow_dispatch`, usable only once the file is on `main`).
* No workflow runs on a push to `feat/p1-b16-operational-readiness`. GitHub checks for it need a pull request to `main` (blocked while PR #4 conflicts)
  or an approved trigger change.
* Known flake: the fake-API suite failed intermittently under heavy machine load in earlier runs (`admin-catalog.spec.ts`, `admin-technicians.spec.ts`);
  it passed 206/206 here. Unfixed, outside P1's scope.

## 7. What the other teams must do

Details per team: [p1-contract-matrix.md](architecture/p1-contract-matrix.md) section 5. That page predates P3 landing on `main`; items marked "not implemented"
there must be re-read against `main`.

| Team | Dependency | State on `main` (2026-11-12) |
|---|---|---|
| P2 | Register one `IncidentAccessPolicy`. Until then the incident audit trail answers 503. Use `CategoryCatalog.requireActive` for new incidents | not implemented on `main` |
| P3 | `AssignmentStrategyCatalog` | **implemented** (`RegisteredAssignmentStrategyCatalog`, registers `LOAD_BASED`, the key P1 seeds, plus `SKILL_BASED`, `ROUND_ROBIN`, `MANUAL`). Settings writes stop returning 503 |
| P3 | `TechnicianWorkloadProvider` | not implemented; P1 does not call it |
| P3 | Confirm the technician contract (`available` is an admin flag, not workload; `maxWorkload` is a ceiling; `escalationChain` nearest-first) and the `defaultMaxWorkload` cap of 100 | pending |
| P4 | Confirm `SlaPolicyProvider.resolve` semantics (422 `MISSING_SLA_CONFIG`, inactive service still resolves), the cross-priority rule, and who owns `unassignedEscalationMinutes` | pending |
| All | Audit: publish through `AuditPublisher` inside the caller's transaction (`Propagation.MANDATORY`) | P1 provides it; use by P2/P3/P4 not verified here |

## 8. Integration: `feat/p1-b18-final-integration` (P1 + `main` + P3's PR #10)

PR #4 (P1) conflicted with `main`. The integration branch merges `main` and P3's PR #10 (`feat/p3-persistence-stores`) into P1, keeping both
sides in every conflict (docs, `frontend/Dockerfile`, P1's stricter `ApplicationStartupIntegrationTest`, P1's `PageQuery` fix with the B13 overflow
guard). No migration collision: V1, V10 to V16 (P1), V20 (P3's incidents table), V30 to V32 (P3).

P1's side of the merge: the assignment-settings tests run against P3's real `RegisteredAssignmentStrategyCatalog` (no fake), the V16 migration test pins
Flyway to target 16, the real-stack assignment spec asserts the real catalog and the seeded `LOAD_BASED`, and the workflows also trigger on this branch.
P3's side (their fixture role `USER` to `EMPLOYEE`, the incident workspace's own mock people, the live client going through `lib/api.ts`) came in with PR #10.

Verified locally on the merged branch (2026-11-12): backend 606 tests, 0 failed, 0 skipped; frontend typecheck, lint and build clean; real-stack suite
64 of 64 (checker OK, secret scan clean); fake suite passes (the 3 skips are P3's opt-in live incidents spec and an existing live-mode throttle skip;
one run under heavy machine load timed out in 4 incident tests that pass when run alone).
