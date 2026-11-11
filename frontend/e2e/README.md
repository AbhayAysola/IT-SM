# Browser regression tests (Playwright)

Covers sign-in/registration/sign-out, the route guard, the P3 incident workspace (`incidents.spec.ts`, `incident-model.spec.ts`), navigation and menus, keyboard focus, WCAG 2.2 AA
(axe) scans, phone-width layout, and the dev-only `/design-system` preview (dialog, sheet, select, dropdown,
tooltip, tabs).

## Run

```bash
cd frontend
npm ci
npx playwright install chromium     # once. Or skip it and use installed Chrome: PW_CHANNEL=chrome
npm run test:e2e                    # next build + start two production servers + run the suite
```

`npm run test:e2e:run` skips the build when `.next/` is current. Debug one file with
`npx playwright test e2e/auth.spec.ts --headed`. Failures keep a trace: `npx playwright show-trace test-results/<dir>/trace.zip`.

The suite starts two servers from one build: `:3000` (a normal production server, where `/design-system`
must 404) and `:3002` (`ENABLE_DESIGN_SYSTEM=true`). Existing servers on those ports are reused locally
(never in CI). Override ports with `E2E_WEB_PORT` / `E2E_DS_PORT`.

> **Pitfall:** whatever already listens on `:3000` (the Compose frontend, an old `next start`) is tested instead of your build, and new routes fail with 404. Stop it (`docker compose down` in `infra/`) or set `E2E_WEB_PORT` / `E2E_DS_PORT`.

## Personas and admin authorization

`newUser(label, role?)` and `newAdmin()` in `e2e/fixtures.ts` create accounts whose `role` the fake
`/api/users/me` returns (`USER` by default, matching the real backend). Admin specs sign in as `ADMIN`;
`admin-authz.spec.ts` covers `EMPLOYEE`, `TECHNICIAN`, `USER`, forged roles, 401/403/5xx and network failures.

In live mode `api.seed` **skips** any test that needs a role other than `USER`: the backend cannot provision
admins yet, and the suite will not fake one through public registration. So in live mode the admin specs report
as skipped, not passed.

## Two modes

| Mode | Command | API |
|---|---|---|
| **Hermetic (default)** | `npm run test:e2e` | Intercepted in the browser by an in-memory fake (`e2e/fixtures.ts`) that mirrors the backend's `register`, `login`, `users/me` and RFC 7807 error shapes. Needs no database, backend or Docker. Safe anywhere, including CI. |
| **Live backend** | `E2E_LIVE_API=1 npm run test:e2e` | The same specs against a real backend. Run it on demand, and whenever the auth contract changes: the fake can only be as right as its author. See requirements below. |

### Live mode requirements

**Backend URL**
- The backend must be reachable at `E2E_API_URL` (default `http://localhost:8080`).
- That URL is compiled into the frontend, so it must equal `NEXT_PUBLIC_API_URL` at build time (`src/lib/api.ts`, same default). If you change one, change both: `NEXT_PUBLIC_API_URL=http://host:port E2E_API_URL=http://host:port E2E_LIVE_API=1 npm run test:e2e`.
- The backend only allows the origin `http://localhost:3000` (CORS in `SecurityConfig`), so leave `E2E_WEB_PORT` at 3000 in live mode.
- The backend must include the shared foundation (merged to `main`). The specs expect a 409 `Email is already registered` and RFC 7807 errors that carry a `message`.

**Test database: use a disposable PostgreSQL 16, never a shared, staging or production one.**
- The tests create real accounts and never delete them. Each run adds about 16, named `e2e-<label>-<timestamp>-<random>@example.test` with the password `Passw0rd!`. Those are weak, publicly documented credentials: they must not exist anywhere that matters.
- An empty database is enough; Flyway migrates it on backend start-up.
- Redis and RabbitMQ are not needed to start the backend (its health endpoint will report DOWN for them; ignore it).

**Throwaway stack (what this was validated with)**

```bash
# 1. database + network (anonymous data volume: removed in step 4 with -v)
docker network create e2e-net
docker run -d --name e2e-pg --network e2e-net -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=itsm postgres:16

# 2. backend jar, built like backend/Dockerfile does (the image bundles Gradle 8.8 + JDK 17; no wrapper download).
#    Run from a checkout that has the foundation.
(cd ../backend && docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/app -w /app -e GRADLE_USER_HOME=/tmp/gradle gradle:8.8-jdk17 \
   gradle bootJar -x test --no-daemon)
docker run -d --name e2e-api --network e2e-net -p 8080:8080 -v "$PWD/../backend/build/libs":/jar:ro \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://e2e-pg:5432/itsm -e SPRING_DATASOURCE_USERNAME=postgres \
  -e SPRING_DATASOURCE_PASSWORD=pw eclipse-temurin:17-jre java -jar /jar/it-sm-0.0.1-SNAPSHOT.jar
# wait until: curl -s -o /dev/null -w '%{http_code}' -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{}'   (prints 400/401, not 000)

# 3. run
E2E_LIVE_API=1 npm run test:e2e

# 4. tear down. -v matters: without it the database survives as a dangling volume holding the test accounts
docker rm -fv e2e-api e2e-pg && docker network rm e2e-net
rm -rf ../backend/build ../backend/.gradle   # build output; both are untracked on branches without backend/.gitignore
```

If you must reuse a long-lived local database instead, remove the test accounts afterwards (and only these):
`DELETE FROM users WHERE email LIKE 'e2e-%@example.test';`

## CI recipe (Node 20, reproducible)

Everything, including the build and both servers, runs on Node 20 inside one throwaway container. The source is
copied in, so nothing is written to your working tree:

```bash
docker run --rm -v "$PWD":/src:ro -e CI=1 node:20-bookworm bash -c '
  mkdir /work && tar -C /src --exclude=node_modules --exclude=.next -cf - . | tar -C /work -xf - && cd /work &&
  npm ci && npx playwright install --with-deps chromium && npm run test:e2e'
```

(Do not use the `mcr.microsoft.com/playwright` image for this: it ships a newer Node than the project targets.)

## Writing tests

- Use `test`/`expect` from `./fixtures` (not `@playwright/test`): it installs the API fake and records API calls.
- Unique accounts: `newUser('label')`. Create one without the UI: `await api.seed(user)`. Sign in through the form: `signIn(page, api, user)`.
- Accessibility: `expect(await axeViolations(page)).toEqual([])`. After opening a menu/dialog, `await settle(page)` first so contrast is measured at full opacity.
- `aria-hidden-focus` is skipped only while a modal Radix layer is open; focus containment is asserted separately in `navigation.spec.ts`.
- Animations are reduced (`reducedMotion: 'reduce'`) for determinism; that is also a check on the `prefers-reduced-motion` CSS.
- `@axe-core/playwright` is pinned to `~4.13`: 4.14+ requires Node 22.12, but this project targets Node 20.
