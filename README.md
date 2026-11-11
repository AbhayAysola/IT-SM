# IT-SM — Intelligent IT Service Management

> An IT incident management system (report → classify → prioritise → assign → investigate → resolve → verify → close) built with Next.js 14, Spring Boot 3 (Kotlin), PostgreSQL, Redis, RabbitMQ, Docker, Kubernetes (Kind), Helm, and Prometheus. University OOP course project.

> **Status:** early. Today the backend has authentication and user profile only; the admin workspace in the frontend runs on mock data. See [Current status](#-current-status).

## 📚 Documentation

| Doc | What it covers |
|---|---|
| [PRODUCT.md](PRODUCT.md) | Users, purpose, principles, accessibility requirements |
| [DESIGN.md](DESIGN.md) | Design system: tokens, typography, components |
| [docs/architecture/foundation.md](docs/architecture/foundation.md) | Shared `common` module: security, errors, API conventions, auditing |
| [docs/architecture/integration-guide.md](docs/architecture/integration-guide.md) | Module ownership (P1–P4), SPI map, per-person recipes |
| [docs/migrations.md](docs/migrations.md) | Flyway version blocks and P1 draft migrations |
| [frontend/e2e/README.md](frontend/e2e/README.md) | Playwright suite (hermetic and live modes) |
| [frontend/src/features/admin/README.md](frontend/src/features/admin/README.md) | Admin workspace, mock persistence, access control |

---

## 🚧 Current status

| Area | State |
|---|---|
| Auth (`/api/auth/*`, `/api/users/me`) | Implemented (boilerplate; hardening planned) |
| Shared `common` module (roles, permissions, errors, SPIs, audit events) | Implemented |
| Audit persistence (`audit_events`) | **Not yet**: events are published, not stored |
| P1 SPI providers (users, categories, services, SLA, assignment settings) | **Not yet** (draft migrations in `docs/migrations/drafts/`) |
| Incident intake, lifecycle/assignment, SLA/notifications (P2–P4) | **Not yet** |
| Admin workspace (frontend) | Built on `localStorage` mock data, no backend endpoints |
| Redis / RabbitMQ | Configured in `config/` and the compose stack; no feature uses them yet |
| Roles | Registration still assigns `USER` (treated as `EMPLOYEE`); no admin bootstrap, so `/admin` shows "Access denied" against the real backend |

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
└──────┬───────────┬───────────────┘
       │           │           │
┌──────▼──┐  ┌────▼────┐  ┌──▼──────────┐
│PostgreSQL│  │  Redis  │  │  RabbitMQ   │
│  :5432   │  │  :6379  │  │  :5672      │
│  (JPA)   │  │(planned)│  │ (planned)   │
└──────────┘  └─────────┘  └─────────────┘
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
| Cache | Redis | 7 |
| Messaging | RabbitMQ | 3 |
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
│   │   ├── app/                 # App Router: / (sign in), /dashboard, /admin/*
│   │   ├── components/          # Navbar, AuthGuard, shadcn/ui primitives (ui/)
│   │   ├── features/admin/      # P1 admin workspace (mock data, see its README)
│   │   ├── lib/                 # api.ts (typed client), auth.ts (JWT helpers)
│   │   └── types/               # Shared TypeScript interfaces
│   ├── e2e/                     # Playwright tests
│   └── Dockerfile
│
├── backend/                     # Spring Boot 3 Kotlin app
│   ├── src/main/kotlin/com/itsm/
│   │   ├── common/              # Shared foundation: model, security, error, api, event, spi
│   │   ├── auth/                # JWT auth, filter, service, controller
│   │   ├── user/                # UserEntity, UserService, UserController
│   │   ├── audit/               # SpringAuditPublisher (publishes only)
│   │   └── config/              # SecurityConfig, RedisConfig, RabbitMQConfig
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── application-docker.yml
│   │   └── db/migration/        # Flyway SQL migrations (V1 only so far)
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

This starts: PostgreSQL · Redis · RabbitMQ · Backend · Frontend

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| Backend API | http://localhost:8080 |
| Health check | http://localhost:8080/actuator/health |
| RabbitMQ UI | http://localhost:15672 (itsm / rabbitmq_password) |

### 3. (Optional) Start monitoring stack

```bash
cd infra
docker compose -f docker-compose.yml -f monitoring/docker-compose.monitoring.yml up
```

| Service | URL |
|---|---|
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3001 (admin / admin) |

---

## 💻 Local Development (without Docker)

### Backend

```bash
cd backend

# Start dependencies only
cd ../infra && docker compose up postgres redis rabbitmq -d && cd ../backend

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
3. Build and load Docker images into the cluster

### Deploy with Helm

```bash
./scripts/deploy.sh
```

### Add local DNS entry

```bash
echo "127.0.0.1 it-sm.local" | sudo tee -a /etc/hosts
```

| Service | URL |
|---|---|
| Frontend | http://it-sm.local |
| Backend API | http://it-sm.local/api |

### Raw K8s manifests (alternative)

```bash
kubectl apply -f infra/k8s/namespace.yaml
kubectl apply -f infra/k8s/
```

---

## 🔌 API Reference

Implemented today. Authenticated endpoints need an `Authorization: Bearer <token>` header. Errors are RFC 7807 `application/problem+json` (see [foundation.md](docs/architecture/foundation.md#4-errors)).

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | public | Register new user (`email`, `name`, `password`) |
| POST | `/api/auth/login` | public | Login, returns JWT |
| GET | `/api/users/me` | required | Current user profile |
| GET | `/actuator/health` | public | Health check |
| GET | `/actuator/prometheus` | public | Prometheus metrics |

Planned endpoints (incidents, admin, SLA, ...) are described per owner in the [integration guide](docs/architecture/integration-guide.md); conventions are in [foundation.md](docs/architecture/foundation.md#5-api-conventions).

### Example: Register + Login

```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password123","name":"John Doe"}'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password123"}'

# Current user
curl http://localhost:8080/api/users/me -H "Authorization: Bearer <token>"
```

---

## ⚙️ Configuration

### Backend (`application.yml`)

| Property | Default | Description |
|---|---|---|
| `app.jwt.secret` | `your-256-bit-secret...` | **Change in production** |
| `app.jwt.expiration` | `86400000` | Token TTL (ms), default 24h |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/itsm` | PostgreSQL URL |
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.rabbitmq.host` | `localhost` | RabbitMQ host |

### Frontend (`.env.local`)

| Variable | Default | Description |
|---|---|---|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | Backend API base URL |

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
- **Append-only audit trail:** state changes publish an `AuditableEvent` inside the business transaction (storage pending).
- **Flyway version blocks per owner** so parallel work never collides: [docs/migrations.md](docs/migrations.md).

### JWT Auth
- Token stored in `localStorage` (key: `it_sm_token`) plus a readable `SameSite=Lax` cookie; planned replacement is described in foundation.md section 7.
- `JwtAuthFilter` validates the token and sets the `SecurityContext` to an `ItsmUserDetails(id, email, role)`.
- Public routes: `/api/auth/**`, `/actuator/**`

---

## 📊 Monitoring

Prometheus scrapes metrics from Spring Boot Actuator at `/actuator/prometheus`.

Available metric groups:
- JVM memory, GC, threads
- HTTP request duration + counts
- HikariCP connection pool
- RabbitMQ listener stats
- Custom business metrics (extendable via `MeterRegistry`)

Import Grafana dashboards:
- **JVM Micrometer**: ID `4701`
- **Spring Boot Statistics**: ID `12685`

---

## 🧪 Running Tests

```bash
# Backend (JDK 17; see foundation.md section 10 for a container recipe)
cd backend
./gradlew test

# Frontend
cd frontend
npm run lint
npm run typecheck
npm run check:contrast
npm run test:e2e        # Playwright, hermetic by default: see frontend/e2e/README.md
```

CI (`.github/workflows/frontend.yml`) runs the frontend checks on PRs to `main`.

---

## 📝 Environment Variables Reference

### Docker Compose (set in `infra/docker-compose.yml`)

| Variable | Service | Value |
|---|---|---|
| `POSTGRES_DB` | postgres | `itsm` |
| `POSTGRES_USER` | postgres | `itsm` |
| `POSTGRES_PASSWORD` | postgres | `itsm_password` |
| `SPRING_PROFILES_ACTIVE` | backend | `docker` |
| `NEXT_PUBLIC_API_URL` | frontend | `http://localhost:8080` |

> ⚠️ **Security**: Change all default passwords before deploying to any shared environment.
