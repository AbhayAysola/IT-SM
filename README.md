# IT-SM — Intelligent IT Service Management

> An IT incident management system (report → classify → prioritise → assign → investigate → resolve → verify → close) built with Next.js 14, Spring Boot 3 (Kotlin), PostgreSQL, Docker, Kubernetes (Kind), Helm, and Prometheus. University OOP course project.

> **Status:** P1 to P4 are all merged: access and administration, incident intake (with AI-assisted categorisation, duplicate hints and a chatbot), lifecycle and assignment, priority, SLA tracking, escalation, notifications and analytics. The incident UI talks to the real backend in every Docker, Compose and Kind build. See [Current status](#-current-status) and the [P1 handoff](docs/p1-handoff.md).

## 📚 Documentation

| Doc | What it covers |
|---|---|
| [PRODUCT.md](PRODUCT.md) | Users, purpose, principles, accessibility requirements |
| [DESIGN.md](DESIGN.md) | Design system: tokens, typography, components |
| [docs/p1-handoff.md](docs/p1-handoff.md) | **Start here for P1**: what it delivers, how it was verified, deferred hardening and its owners, what other teams must do |
| [docs/architecture/foundation.md](docs/architecture/foundation.md) | Shared `common` module: security, errors, API conventions, auditing |
| [docs/architecture/integration-guide.md](docs/architecture/integration-guide.md) | Module ownership (P1–P4), SPI map, per-person recipes |
| [docs/architecture/security.md](docs/architecture/security.md) | Sessions and cookies, CSRF, rate limits, trusted proxies, actuator, probes, HTTPS-only Kubernetes |
| [docs/architecture/p1-contract-matrix.md](docs/architecture/p1-contract-matrix.md) | Every shared SPI and P1 HTTP surface: owner, state, behaviour when absent or failing |
| [docs/architecture/audit-retrieval.md](docs/architecture/audit-retrieval.md) | Audit retrieval API and its privacy rules |
| [docs/architecture/admin-frontend-contract.md](docs/architecture/admin-frontend-contract.md) | The admin frontend's contract with the backend |
| [docs/architecture/p3.md](docs/architecture/p3.md) | P3: lifecycle, assignment, investigation, feedback |
| [docs/architecture/p4.md](docs/architecture/p4.md) | P4: priority, SLA, escalation, notifications, analytics (design) |
| [docs/migrations.md](docs/migrations.md) | Flyway version blocks |
| [frontend/e2e/README.md](frontend/e2e/README.md) | Fake-API Playwright suite (hermetic and live modes) |
| [frontend/e2e-real/README.md](frontend/e2e-real/README.md) | Real frontend + backend + PostgreSQL 16 Playwright suite |
| [frontend/src/features/admin/README.md](frontend/src/features/admin/README.md) | Admin workspace and its backend integration |

---

## 🚧 Current status

| Area | State |
|---|---|
| Auth, sessions (HttpOnly cookie for the web app, Bearer for scripts), first-admin bootstrap | **Done** (P1): [security.md](docs/architecture/security.md) |
| Users and roles, technician profiles, categories, IT services (list and a visual dependency map), SLA policies, assignment settings | **Done** (P1), served by the real admin API and connected to the admin workspace |
| Audit trail (`audit_events`, append-only trigger) and admin retrieval API | **Done** (P1) |
| Incident intake (`/api/incidents`, catalog, AI analysis, chatbot intake) | **Done** (P2) |
| Incident list filters | Priority, category, service, technician, reported-date range and SLA status, on top of state and text search: [p3.md](docs/architecture/p3.md). The incident UI uses the real backend in every Docker, Compose and Kind build; only `npm run dev` and the hermetic e2e suite use browser-only demo data |
| Incident lifecycle, assignment, investigation timeline, feedback | On `main` (P3), including the strategy catalog and workload provider P1 consumes |
| Priority calculation | **Done** (P4 with P2): the report form asks impact and urgency, and P4's `PriorityPolicy` derives the priority (service criticality and dependent services included). See [p4.md](docs/architecture/p4.md) section 1 |
| SLA timers and breach detection | **Done** (P4): `V40`, a scheduled monitor, `GET /api/incidents/{id}/sla`, `GET /api/sla/incidents`, and the incident page's "Service level" card. See the integration guide's P4 section |
| Escalation | **Done** (P4): `V41`, an escalation monitor (breach to the team lead, 150 %/200 % of the target further up, unassigned to the admins), the admin "Escalation" page and the incident's escalation history. See [p4.md](docs/architecture/p4.md) section 3 |
| Notifications | **Done** (P4): `V42`, an in-app inbox (bell and `/notifications`) with simulated email/SMS by priority, for assignments, questions, fixes, reopens, SLA warnings and breaches, and escalations. See [p4.md](docs/architecture/p4.md) section 4 |
| Analytics | **Done** (P4): the admin "Analytics" page and `GET /api/admin/analytics/summary` (SLA compliance by priority, service and category, response and resolution times, escalations, daily volumes, technician workload and ratings). See [p4.md](docs/architecture/p4.md) section 5 |
| Kubernetes / Helm | HTTPS only, build-time API origin, separate liveness/readiness probes. Production hardening is assigned to the deployment owners: [handoff section 5](docs/p1-handoff.md) |
| CI | `backend.yml`, `frontend.yml` and `real-stack.yml` on PRs to `main`; backend and real-stack also on pushes to `main` |

**Data sent to Google.** The incident AI features (categorisation, duplicate hints, chatbot intake) send text to the Google Gemini API when `gemini.api-key` (env `GEMINI_API_KEY`) is set: the new incident's title and description, the chat messages, the category and service lists, and the titles and opening lines of up to 10 *other* open incidents for the duplicate check. The reporter's name and email are not sent. Without a key nothing is sent and the report form is manual. The report page tells users this next to the submit button and in the chat. Details and caveats: [security.md](docs/architecture/security.md#third-party-data-google-gemini).

---

## 📐 Architecture

```
┌──────────────────────────────────┐
│   Frontend: Next.js 14 (TS)      │  :3000
│   App Router · Tailwind CSS      │
└────────────────┬─────────────────┘
                 │ REST API / JWT
┌────────────────▼─────────────────┐
│   Backend: Spring Boot 3 (Kotlin)│  :8080
│   Spring MVC · Security · JPA    │
└────────────────┬─────────────────┘
                 │
        ┌────────▼────────┐
        │   PostgreSQL    │
        │  :5432  (JPA)   │
        └─────────────────┘

Domain events travel through Spring's in-process publisher; PostgreSQL is the only data store.

Optional outbound call: Google Gemini API (incident categorisation, duplicate hints, chatbot), only when `gemini.api-key` is set.
```

---

## 📦 Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Frontend | Next.js (App Router, TypeScript) | 14 |
| Styling | Tailwind CSS | 3.4 |
| Backend | Spring Boot + Kotlin | 3.3 / 1.9 |
| Build Tool | Gradle KTS | 8.8 |
| Database | PostgreSQL | 16 |
| Migrations | Flyway | — |
| Auth | JWT (jjwt) | 0.12.5 |
| Metrics | Micrometer + Prometheus | — |
| Container | Docker + Compose | — |
| Orchestration | Kubernetes (Kind) | — |
| Packaging | Helm | 3 |
| Monitoring | Prometheus + Grafana | — |

---

## 🗂️ Project Structure

```
IT-SM/
├── PRODUCT.md  DESIGN.md        # Product and design-system docs
├── docs/                        # Architecture, integration guide, migration plan
├── frontend/                    # Next.js 14 TypeScript app
│   ├── src/
│   │   ├── app/                 # App Router: / (sign in), /dashboard, /incidents/*, /notifications, /admin/*
│   │   ├── components/          # Navbar, AuthGuard, shadcn/ui primitives (ui/)
│   │   ├── features/admin/      # Admin workspace on the real API (see its README)
│   │   ├── features/incidents/  # Incident workspace: list + filters, detail, report form, chat intake
│   │   ├── features/notifications/  # Bell and inbox
│   │   ├── lib/                 # api.ts (typed client), auth.ts (JWT helpers)
│   │   └── types/               # Shared TypeScript interfaces
│   ├── e2e/                     # Playwright tests
│   └── Dockerfile
│
├── backend/                     # Spring Boot 3 Kotlin app
│   ├── src/main/kotlin/com/itsm/
│   │   ├── common/              # Shared foundation: model, security, error, api, event, spi
│   │   ├── auth/ user/ bootstrap/   # JWT and web sessions, accounts, first-admin bootstrap
│   │   ├── admin/               # Users, technicians, categories, services, SLA policies, assignment settings
│   │   ├── audit/               # Append-only audit trail and its retrieval API
│   │   ├── intake/              # Incident creation, AI analysis, chatbot
│   │   ├── lifecycle/ assignment/ investigation/ feedback/   # State machine, strategies, timeline, ratings
│   │   ├── priority/ sla/ escalation/ notification/ analytics/   # P4
│   │   └── config/              # SecurityConfig and other wiring
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── application-docker.yml
│   │   └── db/migration/        # Flyway SQL migrations (V1 to V42, in per-owner blocks)
│   └── Dockerfile
│
├── infra/
│   ├── docker-compose.yml       # Full local dev stack
│   ├── k8s/                     # Raw Kubernetes manifests
│   ├── helm/                    # Helm chart (parameterized)
│   └── monitoring/              # Prometheus + Grafana configs
│
└── scripts/
    ├── setup-kind.sh            # Bootstrap Kind cluster
    └── deploy.sh                # Helm deploy
```

---

## 🚀 Quick Start — Local Dev (Docker Compose)

### Prerequisites
- Docker + Docker Compose v2
- Node.js 20+ (for local frontend dev)
- JDK 17+ (for local backend dev)

### 1. Copy environment files

```bash
cp frontend/.env.local.example frontend/.env.local
```

### 2. Start the full stack

```bash
cd infra
docker compose up --build
```

This starts: PostgreSQL · Backend · Frontend

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| Backend API | http://localhost:8080 |
| Health check | not published: actuator is on port 8081 inside the compose network (see [Monitoring](#-monitoring)) |

The backend image is multi-architecture, so this builds natively on Apple Silicon as well as on x86 (no `DOCKER_DEFAULT_PLATFORM` needed).

### 3. Create the first administrator and (optionally) demo data

A fresh database has no accounts. Once the stack is healthy, run the one-shot bootstrap service (it refuses if an administrator already exists):

```bash
cd infra
ITSM_BOOTSTRAP_ADMIN_EMAIL=admin@example.com ITSM_BOOTSTRAP_ADMIN_PASSWORD='<a strong password>' \
  docker compose --profile bootstrap run --rm bootstrap-admin
```

For a demo, load sample data through the public API (idempotent, standard-library Python 3 only): 3 employees, 3 technicians (a team lead with two reports, skills in different categories), 6 services with dependencies, stricter per-service SLA policies for the critical ones, and 5 incidents (assigned or in progress).

```bash
ITSM_ADMIN_EMAIL=admin@example.com ITSM_ADMIN_PASSWORD='<the password>' python3 scripts/seed_demo.py   # --url for another backend, --no-incidents to skip incidents
```

Every demo account uses the password in `ITSM_DEMO_PASSWORD` (default `Demo-Pass-123`): `alice.employee@demo.itsm`, `tara.lead@demo.itsm`, and so on. Never run the seeder against a real deployment.

### 4. (Optional) Start monitoring stack

```bash
cd infra
docker compose -f docker-compose.yml -f monitoring/docker-compose.monitoring.yml up
```

| Service | URL |
|---|---|
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3001 (admin / admin), dashboard **IT-SM operations** is provisioned |

---

## 💻 Local Development (without Docker)

### Backend

```bash
cd backend

# Start dependencies only
cd ../infra && docker compose up postgres -d && cd ../backend

# Run
./gradlew bootRun
```

### Frontend

```bash
cd frontend
npm install
npm run dev        # http://localhost:3000
```

---

## ☸️ Kubernetes (Kind)

### Prerequisites
- [Kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)
- [Helm 3](https://helm.sh/docs/intro/install/)
- Docker

### Bootstrap the cluster

```bash
chmod +x scripts/setup-kind.sh scripts/deploy.sh
./scripts/setup-kind.sh
```

This will:
1. Create a Kind cluster with 1 control-plane + 2 workers
2. Install NGINX Ingress Controller
3. Create the `it-sm` namespace and a **self-signed** TLS Secret (`it-sm-tls`, override with `TLS_SECRET_NAME`) for `PUBLIC_HOST`: local demos only, the browser warns once
4. Build and load Docker images into the cluster

### HTTPS and the public origin (read before deploying)

The Kubernetes deployment is **HTTPS only**: the session cookie is `Secure`, and CORS allows only `https://<host>`. Two things are yours to provide; the repository contains no real domain, certificate or issuer (`it-sm.local` is only a placeholder):

1. **A TLS Secret** (`kubernetes.io/tls`) in the `it-sm` namespace, valid for your host, **created before** the ingress can serve HTTPS (`scripts/setup-kind.sh` makes a self-signed one for Kind; for anything else):
   `kubectl -n it-sm create secret tls <name> --cert=<tls.crt> --key=<tls.key>` (or add your own certificate-issuer annotation: `ingress.annotations` in Helm, the annotations block in `infra/k8s/ingress.yaml`).
2. **A frontend image built for that host.** `NEXT_PUBLIC_API_URL` is compiled into the client bundle by `next build`; **changing an environment variable on the running pod does nothing.** Build with the bare public origin (no `/api`, because every request path already starts with `/api`):
   `docker build --build-arg NEXT_PUBLIC_API_URL=https://<host> -t it-sm/frontend:latest frontend` (`scripts/setup-kind.sh` does this from `PUBLIC_HOST`). The default `http://localhost:8080` is for local development only; never deploy an image built with it.

### Deploy with Helm

```bash
export PUBLIC_HOST=<host> TLS_SECRET_NAME=<tls-secret> JWT_SECRET="$(openssl rand -base64 48)"
export POSTGRES_PASSWORD=<choose>   # no default; keep it for later deploys
export TRUSTED_PROXY_CIDRS=10.244.0.0/16   # the ingress controller's pod CIDR (this is Kind's default pod network)
./scripts/setup-kind.sh   # Kind only: cluster, ingress controller and images (frontend built for https://$PUBLIC_HOST)
./scripts/deploy.sh
```

The chart fails with a clear message if `ingress.host`, `ingress.tls.secretName`, the password or `backend.trustedProxy.cidrs` is empty.
It also installs NetworkPolicies (the ingress controller reaches the frontend and the backend API; only the backend reaches PostgreSQL; the backend's management port is open to Prometheus pods only), which need a CNI that enforces them (Kind's default does).
Optional in-cluster observability: set `GRAFANA_ADMIN_PASSWORD` before `./scripts/deploy.sh` to also deploy Prometheus (it discovers the backend pods and scrapes their management port) and Grafana with the **IT-SM operations** dashboard provisioned; reach it with `kubectl -n it-sm port-forward svc/it-sm-grafana 3001:3000`. Neither is on the ingress, and storage is an emptyDir (demo grade). Helm: `monitoring.prometheus.enabled`, `monitoring.grafana.enabled`, `monitoring.grafana.adminPassword`.
The first administrator and demo data: `kubectl -n it-sm exec deploy/it-sm-backend -- sh -c 'ITSM_BOOTSTRAP_ADMIN_EMAIL=... ITSM_BOOTSTRAP_ADMIN_PASSWORD=... java -jar app.jar --itsm.bootstrap-admin=true --server.port=0 --management.server.port=0'`, then `kubectl -n it-sm port-forward svc/it-sm-backend 8080:8080` and `scripts/seed_demo.py` as above.
CI builds both images on every pull request, scans them with Trivy (fixable HIGH/CRITICAL fails the job) and publishes them to GHCR from `main` ([images.yml](.github/workflows/images.yml)); the published frontend image targets `http://localhost:8080`, so a deployment still builds its own for its host.
For production also enable the restricted database role: [security.md, "Database roles"](docs/architecture/security.md#database-roles). `CORS_ALLOWED_ORIGINS` defaults to `https://<ingress.host>`, so it cannot drift from the ingress.

### Add local DNS entry (Kind)

```bash
echo "127.0.0.1 <host>" | sudo tee -a /etc/hosts
```

| Service | URL |
|---|---|
| Frontend | `https://<host>` |
| Backend API | `https://<host>/api` |

### Raw K8s manifests (alternative)

Edit the marked PLACEHOLDERS first (`infra/k8s/ingress.yaml` host and TLS secret name, `infra/k8s/backend-configmap.yaml` `CORS_ALLOWED_ORIGINS` and `TRUSTED_PROXIES`, the passwords in `backend-secret.yaml` and `postgres-secret.yaml` (the pair must match), and the namespaces in `networkpolicy.yaml`), then:

```bash
kubectl apply -f infra/k8s/namespace.yaml
kubectl apply -f infra/k8s/
```

---

## 🔌 API Reference

Authenticated endpoints need `Authorization: Bearer <token>` for scripts and services (token from `POST /api/auth/login` **without** the `X-ITSM-Client` header), or the HttpOnly session cookie for the web app (login with `X-ITSM-Client: web`; see `docs/architecture/security.md`). Errors are RFC 7807 `application/problem+json` (see [foundation.md](docs/architecture/foundation.md#4-errors)).

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | public | Self-register (disabled unless `REGISTRATION_ENABLED=true`); always `202 {"status":"ACCEPTED"}`, no token, then sign in |
| POST | `/api/auth/login` | public | Login, returns JWT |
| POST | `/api/auth/logout` | required | End the session: revokes ALL of the caller's tokens and clears the cookie |
| GET | `/api/users/me` | required | Current user profile |
| GET | `/api/incidents` | required | The caller's incidents (admin: all), paged. Filters, all optional and combined with AND: `states`, `q` (id or title), `priority` (P1 to P4, repeatable), `categoryId`, `serviceId`, `assigneeId`, `createdFrom` / `createdTo` (ISO dates, inclusive), `slaStatus`. A malformed value is a 400, never an empty list |
| GET | `/actuator/health/liveness`, `/readiness` | management port 8081 (or 8080 when no separate port is set) | Kubernetes probes |
| GET | `/actuator/prometheus` | management port 8081 only, no credentials, never published | Prometheus metrics, including `itsm_*` business metrics. On 8080 (no separate port) it needs ADMIN |

The administration endpoints (users, technicians, catalogs, SLA, assignment settings, audit) are listed in the [integration guide](docs/architecture/integration-guide.md); the incident endpoints (`/api/incidents/**`: intake, transitions, assignment, timeline, feedback) are in [p3.md](docs/architecture/p3.md); conventions are in [foundation.md](docs/architecture/foundation.md#5-api-conventions).

### Example: sign in as the first administrator

There is no default account. Create the first admin with the command-mode bootstrap, then use the API:

```bash
# Once, with the backend jar and an empty or existing database:
ITSM_BOOTSTRAP_ADMIN_EMAIL=admin@example.com ITSM_BOOTSTRAP_ADMIN_PASSWORD='<a strong password>' \
  java -jar backend/build/libs/it-sm-0.0.1-SNAPSHOT.jar --itsm.bootstrap-admin=true

# Login for scripts (Bearer token)
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"<the password>"}'

# Current user
curl http://localhost:8080/api/users/me -H "Authorization: Bearer <token>"
```

Self-registration is off unless `REGISTRATION_ENABLED=true` (then `POST /api/auth/register` answers `202` with no token, and you sign in).

---

## ⚙️ Configuration

### Backend (`application.yml`)

| Property | Default | Description |
|---|---|---|
| `app.jwt.secret` (env `JWT_SECRET`) | none | **Required**: >= 32 bytes of random secret; startup fails if missing, short or a placeholder. See `docs/architecture/security.md` |
| `app.jwt.expiration` (env `JWT_EXPIRATION`) | `86400000` | Token TTL (ms), default 24h |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/itsm` | PostgreSQL URL |
| `management.server.port` (env `MANAGEMENT_SERVER_PORT`) | unset (actuator on 8080) | Set to `8081` (compose, Kubernetes and Helm do) to serve actuator on an unpublished port Prometheus can scrape without credentials |
| `spring.flyway.baseline-on-migrate` (env `FLYWAY_BASELINE_ON_MIGRATE`) | `false` | `true` only to adopt an existing database that has no Flyway history |
| `gemini.api-key` (env `GEMINI_API_KEY`) | empty | Enables AI categorisation, duplicate hints and the chatbot (Google Gemini). Without it those endpoints answer 400 and the report form falls back to manual entry. Compose passes it through; in Kubernetes set `backend.gemini.apiKey` (Helm) or `GEMINI_API_KEY` in `backend-secret.yaml` |

### Frontend (`.env.local`)

| Variable | Default | Description |
|---|---|---|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | Backend origin the **browser** calls: the bare origin, no `/api`. Compiled in by `next build` (a runtime variable has no effect), so a deployed image must be built with it: [HTTPS and the public origin](#https-and-the-public-origin-read-before-deploying) |
| `NEXT_PUBLIC_INCIDENTS_API` | unset for `npm run dev` (browser-only demo data); `live` in the Docker image, Compose and Kind builds | `live` makes the incident pages and notification inbox use the backend. Also compiled in at build time. Override a Docker build with `--build-arg NEXT_PUBLIC_INCIDENTS_API=mock` |

### Helm (`infra/helm/values.yaml`)

Override any value with `--set` or a custom `values.yaml`:

```bash
helm upgrade --install it-sm ./infra/helm \
  --set backend.jwt.secret=my-super-secret \
  --set postgres.password=my-pg-password
```

---

## 🏗️ Key Design Decisions

- **Modular monolith with one rule:** a module imports only `com.itsm.common.*`. Cross-module calls use SPIs, cross-module notifications use events. See [foundation.md](docs/architecture/foundation.md).
- **Append-only audit trail:** state changes publish an `AuditableEvent` inside the business transaction (stored append-only in `audit_events`; see [audit-retrieval.md](docs/architecture/audit-retrieval.md)).
- **Flyway version blocks per owner** so parallel work never collides: [docs/migrations.md](docs/migrations.md).

### JWT Auth
- Browser sessions use an `HttpOnly`, `SameSite=Strict` cookie (`itsm_session`); the frontend holds no token. Scripts can send `Authorization: Bearer <token>`. See [security.md](docs/architecture/security.md).
- `JwtAuthFilter` validates the token and sets the `SecurityContext` to an `ItsmUserDetails(id, email, role)`.
- Public routes: `/api/auth/login`, `/api/auth/register`, health probes (see [security.md](docs/architecture/security.md))

---

## 📊 Monitoring

Prometheus scrapes Spring Boot Actuator on the **management port (8081)**, which no `ports:` entry, Service or Ingress publishes. Start the monitoring stack
with the command above; the Grafana dashboard **IT-SM operations** and its datasource are provisioned from `infra/monitoring/grafana/`.

Available metric groups:
- **Business (`itsm_*`):** `itsm_incidents{state}`, `itsm_incidents_open{priority}`, `itsm_sla_open{status}`, `itsm_sla_breaches`, `itsm_escalations`,
  refreshed from the database every 15 s ([security.md](docs/architecture/security.md#business-metrics))
- HTTP request rate and latency histograms (p50/p95/p99 in the dashboard)
- JVM memory, GC, threads; HikariCP connection pool; CPU

The dashboard panels: open incidents, open P1, awaiting assignment, breached SLAs, breaches ever, escalations, open incidents by priority and state, SLA
status, request rate, latency percentiles, 5xx rate, heap, connection pool and CPU. Not deployed in Kubernetes by this repo: point your Prometheus at the
backend pods' port 8081 and set `networkPolicy.prometheus` to where it runs.

---

## 🧪 Running Tests

```bash
# Backend (JDK 17 or 21, not 26: Kotlin 1.9 cannot parse its version; Docker running: the integration tests use PostgreSQL 16 Testcontainers)
cd backend
ITSM_REQUIRE_DOCKER=true ./gradlew clean build   # fails instead of skipping when Docker is missing

# Frontend
cd frontend
npm run lint
npm run typecheck
npm run check:contrast
npm run test:e2e        # fake-API Playwright suite: see frontend/e2e/README.md
npm run test:real-stack # real frontend + backend + PostgreSQL 16: see frontend/e2e-real/README.md
```

CI: `backend.yml` (unit and Testcontainers tests, Flyway on a clean PostgreSQL 16), `frontend.yml` (lint, typecheck, contrast, fake-API e2e) and
`real-stack.yml` (the real-stack suite) run on pull requests to `main`; `backend.yml` and `real-stack.yml` also run on pushes to `main`.

---

## 📝 Environment Variables Reference

### Docker Compose (set in `infra/docker-compose.yml`)

| Variable | Service | Value |
|---|---|---|
| `POSTGRES_DB` | postgres | `itsm` |
| `POSTGRES_USER` | postgres | `itsm` |
| `POSTGRES_PASSWORD` | postgres | `itsm_password` |
| `SPRING_PROFILES_ACTIVE` | backend | `docker` |
| `NEXT_PUBLIC_API_URL` | frontend (build arg) | `http://localhost:8080` |
| `NEXT_PUBLIC_INCIDENTS_API` | frontend (build arg) | `live` |
| `MANAGEMENT_SERVER_PORT` | backend | `8081` (not published) |

> ⚠️ **Security**: these are throwaway development values. Kubernetes and Helm have **no** default passwords any more (they must be supplied), and the restricted database role is available as an overlay: `docker compose -f docker-compose.yml -f docker-compose.roles.yml up` ([security.md](docs/architecture/security.md#database-roles)).
