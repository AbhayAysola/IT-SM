# Real-stack end-to-end tests

The **real** Next.js production build, the **real** Spring Boot backend and a **real** PostgreSQL 16, driven by Chromium. Nothing
is mocked, intercepted or faked, no security rule is relaxed, and the browser uses the real HttpOnly session cookie.

This is a separate layer from the fast hermetic suite in `../e2e` (fake API, `frontend.yml`), which is untouched and stays the quick
feedback loop. CI runs this one in `.github/workflows/real-stack.yml` ("Real stack / Frontend ↔ backend on PostgreSQL 16").

## What it covers (`manifest.json` is the source of truth)

| Spec | Covers |
|---|---|
| `session` | HttpOnly/SameSite=Strict/Path=/api cookie, no token in the body or in script-visible storage, reload, real logout and server-side revocation (also of the user's other sessions), CSRF header + Origin rules, malformed Origin, Bearer vs cookie precedence, CORS, login CSRF |
| `rbac` | anonymous 401, EMPLOYEE and TECHNICIAN 403 on every admin API (reads and writes, nothing created), route guard in the browser, ended-session detection |
| `users` | create, duplicate email, role change, deactivate/activate (lock-out and session end), password reset, session revoke, optimistic lock, self-protection, no delete |
| `technicians` | profile + skills, duplicate/non-technician refusal, optimistic lock, role change blocked while the profile is active |
| `catalog` | categories and services: validation, uniqueness, dependency cycles, optimistic lock, (de)activation |
| `sla` | service-specific policies, validation, duplicates, effective lookup, optimistic lock, deactivation |
| `assignment` | P3's real strategy catalog: API reports AVAILABLE with the registered keys (LOAD_BASED seeded), unknown key refused, editable UI, no overview warning |
| `audit` | attributable events, **privacy** (no password/hash/token/cookie), admin-only, read-only, paging contract (B13: an offset past 2^31−1 is `INVALID_INPUT`; a page that does not fit an Int is `MALFORMED_REQUEST`), audit page |
| `ratelimit` | per-email login limit at its production value (5 → 429 + Retry-After), per-account scope, registration (identical 202, no escalation), and proof that the CI throttle arguments bind |

## Skips, exclusions and what a pass means

* **There are no skips.** The suite has no `test.skip`, `fixme`, conditional skip or `testInfo.skip`; it fails (not skips) when the
  backend, a persona or the web server is missing.
* **Exclusions are documented, not skipped.** `manifest.json` lists what is deliberately not in this suite (each with the reason and
  where it *is* covered): accessibility, design-system, responsive, navigation, favicon, the admin-client unit tests, the fake-backed
  admin specs, the register-per-IP limit, the ABSENT/FAILED catalog states and Secure-cookie/TLS behaviour.
* `scripts/check-real-stack-results.mjs` fails the job on any skipped, flaky, failed or missing test, on a spec that is not in the
  manifest, on a manifest entry whose test count changed, and on any `.skip/.fixme/.fail/.only` in the source. It prints
  PASSED / SKIPPED / EXCLUDED separately. **Adding or removing a test means editing `manifest.json`** (deliberately).

## How it is provisioned (no test-only production code)

1. A disposable, **empty** PostgreSQL 16.
2. The first ADMIN is created by the product's own **command-mode bootstrap** (`--itsm.bootstrap-admin=true`, see
   `docs/architecture/security.md`), from a random password that exists only in the environment.
3. `global-setup.ts` signs in as that admin through the real API and creates the EMPLOYEE and TECHNICIAN personas and the
   technician profile with the real admin endpoints. Their random passwords go into `process.env` for the workers; nothing is written
   to disk. Each test then creates its **own uniquely named data** (categories, services, users...), so the suite can be re-run against
   a database that already holds earlier runs' data.
4. The backend runs with the `dev` profile and `AUTH_COOKIE_SECURE=false` **only because CI is plain HTTP**, `REGISTRATION_ENABLED=true`,
   and **only the IP-wide throttles raised** (`login-ip.max-failures`, `register-ip.max-attempts`), because every test comes from one
   address. The per-email login limit, CORS (`http://localhost:3000`), CSRF and every authorization rule are production's.
   `ratelimit.spec.ts` proves both halves (the raised limits bind, the per-email limit does not).

One worker, no retries (a flaky test fails the job): the assignment/SLA settings are shared singletons.

## Secret safety

No trace, video or screenshot (they record cookies and passwords). Assertions about secrets use `support/safe.ts` so a failure never
prints the value. Before any artifact upload `scripts/scan-real-stack-secrets.mjs` scans the report and the backend/bootstrap logs
for the generated passwords, JWTs, bcrypt hashes, session-cookie values, Bearer tokens and the generated-password format; on a hit it
names the rule and file, deletes the report directory and fails the job, so nothing is uploaded. Artifacts are uploaded on failure only.

## Run it locally

```bash
# 1. an EMPTY, DISPOSABLE PostgreSQL 16 (never a shared one)
docker run -d --name rs-pg -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=itsm -p 5432:5432 postgres:16

# 2. build, then start the backend (bootstraps the admin, raises only the IP-wide throttles)
(cd ../backend && ./gradlew bootJar -x test)
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/itsm SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=pw
export JWT_SECRET="$(openssl rand -base64 48)" E2E_ADMIN_EMAIL=admin@example.test E2E_ADMIN_PASSWORD="Aa1!$(openssl rand -hex 9)"
e2e-real/stack.sh backend-up

# 3. build the frontend (the API URL is compiled in) and run
NEXT_PUBLIC_API_URL=http://localhost:8080 npm run build
npm run test:real-stack
npm run check:real-stack      # the pass/skip/exclusion gate CI applies
npm run scan:real-stack       # the secret scan CI applies before uploading

# 4. clean up
e2e-real/stack.sh backend-down && docker rm -fv rs-pg
```

The web app must be on `:3000` (the backend's default CORS origin). To use another port, set `E2E_WEB_PORT` **and**
`CORS_ALLOWED_ORIGINS=http://localhost:<port>` for the backend. If something already listens on the port the run fails instead of
silently testing it.
