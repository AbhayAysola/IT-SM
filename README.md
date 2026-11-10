# IT-SM — Full-Stack Boilerplate

> **User Management + Order Processing** reference app wiring together Next.js 14, Spring Boot 3 (Kotlin), PostgreSQL, Redis, RabbitMQ, Docker, Kubernetes (Kind), Helm, and Prometheus.

> 📚 Module rules, SPIs and integration guides for each teammate: [docs/architecture/](docs/architecture/foundation.md) · migration plan: [docs/migrations.md](docs/migrations.md)

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
│  (JPA)   │  │ (cache) │  │ (events)    │
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
├── frontend/                    # Next.js 14 TypeScript app
│   ├── src/
│   │   ├── app/                 # App Router pages
│   │   │   ├── page.tsx         # Landing / Login / Register
│   │   │   ├── dashboard/       # Orders dashboard (protected)
│   │   │   ├── products/        # Product catalog (protected)
│   │   │   └── orders/new/      # Place order (protected)
│   │   ├── components/          # Navbar, AuthGuard, ProductCard, OrderTable
│   │   ├── lib/                 # api.ts (typed client), auth.ts (JWT helpers)
│   │   └── types/               # Shared TypeScript interfaces
│   └── Dockerfile
│
├── backend/                     # Spring Boot 3 Kotlin app
│   ├── src/main/kotlin/com/itsm/
│   │   ├── auth/                # JWT auth, filter, service, controller
│   │   ├── user/                # UserEntity, UserService, UserController
│   │   ├── product/             # ProductEntity, ProductService (Redis cached)
│   │   ├── order/               # OrderEntity, OrderService, OrderController
│   │   ├── messaging/           # OrderEventPublisher + OrderEventConsumer
│   │   └── config/              # SecurityConfig, RedisConfig, RabbitMQConfig
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── application-docker.yml
│   │   └── db/migration/        # Flyway SQL migrations
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

All endpoints (except auth) require `Authorization: Bearer <token>` header.

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` | Register new user |
| POST | `/api/auth/login` | Login, returns JWT |
| GET | `/api/users/me` | Get current user profile |
| GET | `/api/products` | List all products (Redis cached) |
| GET | `/api/products/{id}` | Get single product |
| POST | `/api/orders` | Create order (publishes RabbitMQ event) |
| GET | `/api/orders` | List current user's orders |
| GET | `/api/orders/{id}` | Get order by ID |
| GET | `/actuator/health` | Health check |
| GET | `/actuator/prometheus` | Prometheus metrics |

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

# Create Order
curl -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"items":[{"productId":1,"quantity":2}]}'
```

---

## ⚙️ Configuration

### Backend (`application.yml`)

| Property | Default | Description |
|---|---|---|
| `app.jwt.secret` (env `JWT_SECRET`) | none | **Required**: >= 32 bytes of random secret; startup fails if missing, short or a placeholder. See `docs/architecture/security.md` |
| `app.jwt.expiration` (env `JWT_EXPIRATION`) | `86400000` | Token TTL (ms), default 24h |
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

### Order Flow (RabbitMQ)
1. `POST /api/orders` → saves order with status `PENDING`
2. `OrderEventPublisher` → publishes to `itsm.exchange` with routing key `order.created`
3. `OrderEventConsumer` → listens on `itsm.orders` queue → updates status to `PROCESSING`

### Redis Caching
- `ProductService.getAll()` is annotated `@Cacheable(value = ["products"])`
- Cache TTL: 10 minutes
- Cache is evicted on product updates

### JWT Auth
- Token stored in `localStorage` (key: `it_sm_token`)
- `JwtAuthFilter` intercepts all requests, validates token, sets `SecurityContext`
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
# Backend
cd backend
./gradlew test

# Frontend
cd frontend
npm run lint
```

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
