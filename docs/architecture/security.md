# Authentication, roles and user administration (P1, Batch 5; hardened in B14)

## Roles and permissions

Exactly three roles, stored in `users.role` (CHECK-constrained): `EMPLOYEE`, `TECHNICIAN`, `ADMIN`. There is no `USER` role;
migration `V15` mapped legacy `USER` rows to `EMPLOYEE`, and `Role.fromStored` still maps a stray `USER` for safety.
The permission model (`RolePermissions`) is unchanged: EMPLOYEE reports and follows its own incidents, TECHNICIAN works
assigned incidents, ADMIN holds every permission (`USER_MANAGE`, `CATALOG_MANAGE`, `SLA_CONFIGURE`, ...).

## Where authorization is decided (B9)

Two layers, both required to pass:

1. **Filter chain (`SecurityConfig`)**: URL rules for every admin area, mirroring the controller permissions
   (`/api/admin/users|technicians` -> `USER_MANAGE`, `categories|services` -> `CATALOG_MANAGE`, `sla-policies` ->
   `SLA_CONFIGURE`, `assignment-settings` -> `ASSIGNMENT_CONFIGURE`, `audit-events` -> `AUDIT_VIEW`; any other
   `/api/admin/**` path is ADMIN-only, so a new admin controller fails closed until it is listed).
2. **Method security (`@PreAuthorize` on each controller)**: unchanged, the fine-grained check.

The URL layer exists because `@PreAuthorize` runs after the request body is bound and validated: with the annotation alone,
an authenticated EMPLOYEE or TECHNICIAN posting an invalid body to an admin endpoint received 400 with field-level
validation messages rather than 403. `RouteAuthorizationMatrixIntegrationTest` discovers every `/api` route at runtime and
fails if a protected route answers an anonymous caller with anything but 401, or a non-admin with anything but 403 (even
with an empty body). It also checks the specific permission, not just refusal: every admin handler's `@PreAuthorize` must name
exactly its area's permission, a principal holding only that permission must reach every route of the area with a valid
request, and a principal holding only any other admin permission must get 403. A future endpoint, or a route wired to the
wrong permission, fails the build. Add new admin permissions to both layers, and a probe for each new route to the test.

## Public registration (B14a)

`POST /api/auth/register {email, password, name}`. **Disabled by default**: `app.registration.enabled` (env `REGISTRATION_ENABLED`) is
`false` in `application.yml`; a disabled endpoint answers `404 RESOURCE_NOT_FOUND` ("Self-registration is not available") for every
well-formed request, new or existing address alike, and does no password hashing. The local docker-compose stack turns it on
(`REGISTRATION_ENABLED` defaults to `true` there), the k8s ConfigMap and the Helm chart set it to `false`. **Recommendation: keep it
disabled in production until email verification exists**, and create accounts with `POST /api/admin/users`.

When enabled, every well-formed request gets the SAME answer:

```
HTTP 202   {"status":"ACCEPTED"}      (no token, no Location header, no account-existence-specific text)
```

A new address creates an `EMPLOYEE` (the DTO has no role field; unknown JSON properties are ignored). An address that already has an
account is left completely untouched: no password, role, name, state or token-version change, and no audit event. The password is hashed
on both paths so the dominant cost (BCrypt) is the same; a unique-index race (two requests for one new address) gives the same 202 and
exactly one account and one `USER_REGISTERED` event. Malformed input is still `400 VALIDATION_FAILED`, which does not depend on whether
the email exists. Email is trimmed and lower-cased; uniqueness is case-insensitive. Password: 8-128 chars and at most 72 UTF-8 bytes.
Because the response carries no token, **the caller must sign in afterwards** (the frontend shows a neutral message and switches to
the sign-in form; it never assumes an account was created).

**What this does NOT solve.** Without email verification a registrant can sign in immediately, so someone who registers an address with
a password and then logs in with that password learns whether the address was new (login succeeds) or taken (401). That residual
signal costs one probe per registration, is throttled (10 per hour per client address) and creates accounts as a side effect, but it is
real. Only verification (the owner of the mailbox confirms) removes it, which needs a mail dependency and is not part of B14. Cross-endpoint
enumeration is therefore NOT fully solved while public self-registration is enabled. Remaining timing differences: the new path also
inserts a row and an audit event; the request count per second and the BCrypt cost dominate, but the signal is not zero.

## Login

`POST /api/auth/login`. Unknown email, wrong password, inactive account and over-long password all return the same
`401 INVALID_CREDENTIALS "Invalid email or password"` (no account enumeration, no disabled-account oracle). Spring's
`DaoAuthenticationProvider` spends BCrypt time even for unknown users, which evens out timing.

Login is also **rate limited** (below). A caller over the limit gets `429 RATE_LIMITED`, not 401, even with the correct password.

## JWT and token invalidation

* Secret: `JWT_SECRET` (property `app.jwt.secret`), **required, no default**. Startup fails if it is missing, blank, under
  32 bytes, contains a known placeholder (`change-me`, `your-256-bit`, `replace-in-production`, ...) or has fewer than 8
  distinct characters. Generate one with `openssl rand -base64 48`. `JWT_EXPIRATION` (ms, default 86 400 000).
  docker-compose, the k8s Secret and the Helm chart all use `JWT_SECRET` (previously the k8s/Helm value was ignored because
  Spring read `APP_JWT_SECRET`, so those deployments ran on the hard-coded default).
* Token claims: `sub` = user id, `tv` = `users.token_version`. The role is NOT a claim.
* `JwtAuthFilter` loads the user by id on every request and accepts the token only if the user exists, is `active`, has a
  valid role and `token_version` equals `tv`. Anything else (including a database error) leaves the request unauthenticated
  -> 401 via the entry point. It never throws and never echoes an exception message.
* `token_version` is incremented, in the same transaction as the change and its audit row, on: role change, deactivation,
  reactivation (so old tokens can never revive), password reset, explicit revoke.

## `GET /api/users/me`

```json
{ "id": 7, "email": "a@campus.example", "name": "A", "role": "EMPLOYEE", "active": true, "createdAt": "2026-11-10T09:00:00" }
```
`role` is always read from the database (never from the token or a client value) and is always one of the three canonical
names. 401 for anonymous, deactivated, deleted or stale-token callers. The existing frontend `AuthGuard(requiredRole)`
already calls this endpoint and compares `role`; with B5 an `ADMIN` account finally matches.

## Admin user API (`/api/admin/users`, `USER_MANAGE`, ADMIN only)

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | page (`page,size,sort,dir`), filters `search` (email/name), `role`, `active` |
| GET | `/{id}` | one user |
| POST | `/` | create `{email,name,password,role}` -> 201 + Location |
| PUT | `/{id}` | `{email,name,version}` |
| PUT | `/{id}/role` | `{role,version}` — bumps token version |
| POST | `/{id}/activate`, `/{id}/deactivate` | `{version}` — bump token version |
| POST | `/{id}/reset-password` | `{password}` — bumps token version |
| POST | `/{id}/revoke-sessions` | bumps token version |

Responses: `{id,email,name,role,active,version,createdAt,updatedAt}` (no hash, no token version). Errors: 400 validation /
unknown role / bad paging, 401, 403, 404, 409 (`DUPLICATE_RESOURCE`, `CONCURRENT_MODIFICATION` for a stale `version`,
`CONFLICT` for the safety rules). No DELETE: accounts are deactivated so audit references stay meaningful.

Safety rules: an admin cannot deactivate or demote **themselves** (another admin must); the **last active ADMIN** can never be
deactivated or demoted. The check runs under advisory lock `7310003`, shared with bootstrap, so concurrent requests cannot both
pass. Optimistic locking: `@Version` on the entity plus the `version` field in the request.

## Technician profile administration permission

`/api/admin/technicians` uses the existing `USER_MANAGE` permission (ADMIN only): technician profiles are part of user
administration and no narrower grant is needed, so no `TECHNICIAN_MANAGE` permission was added. Changing a user's role stays in
`/api/admin/users`; a profile never alters the role. See the integration guide for the profile model.

## Audit

Actions (`entityType USER`, actor = the admin, `targetUserId` in metadata): `USER_CREATED`, `USER_UPDATED`, `USER_ROLE_CHANGED`
(previous/new state = role), `USER_ACTIVATED`/`USER_DEACTIVATED` (INACTIVE/ACTIVE), `USER_PASSWORD_RESET`,
`USER_SESSIONS_REVOKED`, `USER_REGISTERED` (actor = the new user), `USER_ADMIN_BOOTSTRAPPED` (system actor `ADMIN_BOOTSTRAP`).
Rows are written through `SpringAuditPublisher` inside the mutation's transaction. Personal data (email, name) and every
secret stay out of metadata: `USER_UPDATED` records field names only.

**Failed logins are NOT audited yet** (throttle events go to logs and a metric, never to the audit table). `SpringAuditPublisher` is `MANDATORY` (needs a business transaction) and a login
failure has none. Proposal (not implemented, needs a decision): a separate `SecurityEventPublisher` that writes in its own
`REQUIRES_NEW` transaction (or only to structured logs), with a per-IP/per-account throttle so it cannot be used to flood the
audit table. Until decided, failed logins are not recorded anywhere.

## First ADMIN bootstrap

Run once per environment, as a one-off command. It is a process mode, not an endpoint:

```bash
export JWT_SECRET=...                       # same variables the service needs (datasource etc.)
export ITSM_BOOTSTRAP_ADMIN_EMAIL=first.admin@your-org.example
export ITSM_BOOTSTRAP_ADMIN_PASSWORD='...'  # 8-72 bytes; from a secret store, not shell history
export ITSM_BOOTSTRAP_ADMIN_NAME='Administrator'   # optional
java -jar app.jar --spring.main.web-application-type=none --itsm.bootstrap-admin=true
# container: docker run --rm --env-file ... it-sm/backend --spring.main.web-application-type=none --itsm.bootstrap-admin=true
```

Exit codes: `0` created, `2` missing `ITSM_BOOTSTRAP_ADMIN_EMAIL`/`_PASSWORD`, `1` refused or failed. It refuses if **any** ADMIN
row exists (even inactive) or the email already belongs to an account (no silent promotion). Check and insert run under an
advisory lock, so concurrent runs create exactly one admin. The password is hashed with the application's `PasswordEncoder`;
neither password nor hash is logged or audited. The runner bean does not exist unless `itsm.bootstrap-admin=true`, so normal
startup never runs it (do not put that flag in a Deployment's args or env). Remove the password variable afterwards.
Further admins: `POST /api/admin/users` with role `ADMIN`.

## Browser sessions: HttpOnly cookie, CSRF and logout (B14b)

Two kinds of client, one JWT, two ways to carry it:

| | Browser (the web app) | Non-browser client (script, service, test) |
|---|---|---|
| Login | `POST /api/auth/login` with **`X-ITSM-Client: web`** | `POST /api/auth/login` with **no** such header |
| Response | `200 {"user": {...}}` + `Set-Cookie`. **No token in the body.** | `200 {"token": "...", "user": {...}}`, **no cookie** (unchanged) |
| Authenticates with | the `itsm_session` cookie, sent by the browser | `Authorization: Bearer <token>` |
| CSRF rules | yes (below) | none: a browser never attaches a Bearer header by itself |

There is no separate token endpoint. A header value other than exactly `web` is `400 INVALID_INPUT` (field `X-ITSM-Client`, "must be 'web'
when present"); nothing is authenticated and nothing is counted against the login throttle. **`X-ITSM-Client` only selects the response
mode and doubles as the CSRF header. It is not a credential and not proof of a trusted origin**: any client can send it. Origin and
CORS are what restrict browsers, the credentials are what authenticate.

**The cookie** (`app.auth.cookie.name`, default `itsm_session`): the same JWT, `HttpOnly` (scripts cannot read it), `SameSite=Strict`,
`Path=/api` (never sent for pages), `Max-Age` equal to the JWT lifetime (`JWT_EXPIRATION`, default 24 h; unchanged, no refresh), `Secure`,
no `Domain` (host-only). The frontend therefore holds no token anywhere: not in `localStorage`, `sessionStorage`, `document.cookie`, or a
login response body. After a reload it calls `GET /api/users/me`; the user object it caches in memory is display-only and never proves
anything (the old `it_sm_token` / `it_sm_user` storage and the old JS-readable `it_sm_token` cookie are purged on load).

**Secure in production.** `app.auth.cookie.secure` (env `AUTH_COOKIE_SECURE`) is `true` by default. `false` is accepted **only** when the
`dev` or `local` Spring profile is active; with any other configuration (production has neither) **the application refuses to start**. So a
stray environment variable cannot weaken a real deployment. Browsers drop `Secure` cookies from plain-HTTP origins (except `localhost`), so a
deployment needs HTTPS (TLS terminated at the ingress or load balancer). Local HTTP development: `SPRING_PROFILES_ACTIVE=dev` plus
`AUTH_COOKIE_SECURE=false` (the docker-compose stack does this).

**Precedence (deterministic).** If an `Authorization` header is present only it is considered: a `Bearer` credential is validated, anything
else (Basic, malformed) leaves the request unauthenticated. An invalid Bearer credential **never falls back to a valid cookie**. Only with no
`Authorization` header is the cookie tried. Tampered, expired, unsigned or stale-version cookies are `401`, exactly like Bearer tokens.

**CSRF.** A request authenticated *by the cookie* with an unsafe method (everything but `GET`, `HEAD`, `OPTIONS`: so `POST`, `PUT`, `PATCH`,
`DELETE`) must carry **both**: `X-ITSM-Client: web` (a custom header: a cross-site page cannot send it without a CORS preflight, which is
answered only for allowed origins) **and** an `Origin` header that **exactly** equals an entry of the allow-list `app.cors.allowed-origins`
(`CORS_ALLOWED_ORIGINS`; no wildcard, prefix or subdomain matching; a missing, empty or `null` Origin is refused). Failure is a generic
`403 FORBIDDEN "Request rejected"` that never says which check failed (the reason is logged). SameSite is an extra layer, not the defence.
The web login is protected against **login CSRF** too: web mode needs the allowed `Origin` (checked before any credential or throttle work)
and a JSON body (`415` otherwise), so another site cannot sign a victim into an attacker's account. Browsers send `Origin` on every
cross-origin request and on same-origin `POST/PUT/PATCH/DELETE`, so legitimate use is unaffected. Because the CORS layer already refuses
foreign origins, the application-level Origin check is a second, independent layer (it also catches same-origin-looking requests whose origin
is not listed, and requests with no Origin at all). **`CORS_ALLOWED_ORIGINS` must therefore list the public origin of the web app in every
deployment**, including a same-origin one behind an ingress (e.g. `https://it-sm.example.com`).

**CORS.** `allowCredentials` is on, origins are the explicit list only (`*` is rejected at startup), allowed headers are
`Authorization, Content-Type, Accept, X-ITSM-Client`. A browser on any other origin gets no `Access-Control-Allow-*` header and cannot make
a credentialed call.

**Logout.** `POST /api/auth/logout` (authenticated; CSRF-protected when the caller used the cookie) increments the user's `token_version`
and answers `200 {"status":"LOGGED_OUT"}` with a `Set-Cookie` that clears the cookie (same name, `Path=/api`, `Max-Age=0`, same
`HttpOnly`/`Secure`/`SameSite`). **This revokes every token and session of that user, not only the browser that logged out**: all other
browsers, all Bearer tokens, all devices stop working at once, because validity is "the token's version equals the user's current version".
The bump is atomic and also advances the row `version`, so an admin editing an older copy of the user gets `409 CONCURRENT_MODIFICATION`
instead of silently restoring the old token version. A client that cannot reach the server clears its own state but the cookie then stays
valid until it expires or a later logout succeeds. Logout is not audited (it would let any user flood the audit table).

**What this protects, and what it does not.** HttpOnly stops a script from *reading* the session token, so an XSS bug can no longer steal it
and replay it elsewhere. It does **not** make XSS harmless: a malicious script running in the app's own origin can still make authenticated
requests as the user from inside their browser (the cookie is attached automatically, and the CSRF checks accept same-origin calls). Content
Security Policy, output encoding and dependency hygiene remain necessary; none of them is added by this batch.

## Refresh tokens: status and plan

**Not implemented, on purpose** (B14b kept the 24 h JWT and added no refresh rotation). The browser session is now an HttpOnly cookie
(next section); the plan below remains the proposal for short-lived access tokens with rotating refresh tokens. It would need a
migration (`V17` or later) and is a separate batch. Proposed contract:

1. `POST /api/auth/login|register` -> JSON `{accessToken (15 min), user}` + `Set-Cookie: refresh=...; HttpOnly; Secure;
   SameSite=Strict; Path=/api/auth`. Access token kept in memory only.
2. `POST /api/auth/refresh` (cookie) rotates the refresh token; `POST /api/auth/logout` revokes it. Both require a CSRF
   header (double-submit) and an `Origin` allow-list check; CSRF protection must then be enabled for those paths.
3. Table `refresh_tokens(id, user_id, token_hash, family_id, expires_at, revoked_at, replaced_by, created_at)`; only SHA-256
   hashes stored; presenting an already-rotated token revokes its whole family (reuse detection); deactivation, role change,
   password reset and revoke-sessions revoke all families of the user.
4. Frontend: replace `lib/auth.ts` storage, add a silent-refresh interceptor in `lib/api.ts`, drop the cached role.

## Other hardening and known limitations

* **Rate limiting:** see "Authentication rate limiting" below. There is no account lockout and no email-only limit.
* **Actuator**: `/actuator/health/**` and `/actuator/info` are public; health details are `when-authorized` (ADMIN only);
  all other actuator endpoints require ADMIN. **Prometheus now gets 401/403 when scraping `/actuator/prometheus`**
  (`infra/monitoring/prometheus.yml` sends no credentials). Recommended follow-up: serve actuator on a separate management
  port that is not exposed publicly, then permit it there.
* **CORS**: explicit origins from `CORS_ALLOWED_ORIGINS` (comma-separated, default `http://localhost:3000`, `*` refused),
  headers limited to `Authorization, Content-Type, Accept`.
* **CSRF** is disabled because auth is a Bearer header with no server session; revisit with cookie refresh tokens.
* No self-service password change or "forgot password" yet; the admin reset is the only path.
* Email addresses are not verified on registration.
* `/api/auth/**` was previously `permitAll`; now only `login` and `register` are.

## Follow-up design: separate management port (not implemented)

Today actuator shares port 8080 with the API and is protected by the application's own rules (health/info public, the rest
ADMIN). That is why Prometheus can no longer scrape `/actuator/prometheus` unauthenticated. Proposed replacement, with no
change to API authentication:

1. `management.server.port=8081` (and `management.endpoints.web.exposure.include=health,info,prometheus`; drop `metrics`).
   Probes keep using `/actuator/health` on 8081 (update the compose healthcheck and the k8s/Helm probe ports together).
2. Port 8081 is NOT published by the Service/Ingress/compose `ports:`. Reachability is limited by infrastructure: a k8s
   `NetworkPolicy` allowing only the Prometheus pods (and kubelet probes), or an internal-only compose network.
3. Spring Security keeps `/api/**` rules as they are; the management port can then `permitAll` health and prometheus because
   it is unreachable from outside, or stay authenticated if no network control is available.
4. Test: an integration test asserting 8080 serves no `/actuator/prometheus` and 8081 does.

## Handoff notes for the frontend owner

* `frontend/src/types/index.ts` still says "The backend currently issues only `USER`". Since B5 the backend returns
  `EMPLOYEE`, `TECHNICIAN` or `ADMIN` (never `USER`); update that comment and consider narrowing `User.role` to `Role`.
* `user` objects now include `active: boolean`.
* Tokens issued before B5 are rejected once (401), so existing sessions must sign in again.
* Refresh-token migration needs the contract above agreed with the frontend first.

## Authentication rate limiting (B14a)

In-process, bounded, injectable-clock counters (`com.itsm.auth.ratelimit`). No Redis, no new dependency.

| Scope (key) | Counted | Limit | On exceeding |
|---|---|---|---|
| Login, client IP + normalised email | failed logins | 5 per 15 min | `429 RATE_LIMITED` + `Retry-After` |
| Login, client IP | failed logins | 40 per 15 min | same |
| Registration, client IP | every attempt (valid or not) | 10 per hour | same |

There is **no email-only lockout** (it would let anyone lock a victim out from anywhere). The pair limit means the legitimate owner,
connecting from another address, is never locked out by someone guessing their password elsewhere.

* **Order.** Registration is charged by a filter before the body is read. Login is charged before any password work, so a locked-out
  caller is refused **even with the correct password**, and a refused attempt costs no BCrypt. Attempts are counted up front, atomically,
  and refunded when they turn out fine, so parallel guesses cannot race past the limit. A success clears only that IP+email counter and
  gives the attempt back to the IP counter. An attempt that fails for a non-credential reason (database outage) is not counted.
* **Indistinguishable.** Keys use the normalised email whether or not an account exists, so known and unknown accounts are throttled
  identically. The 429 body is generic: it never says which limit fired. `Retry-After` is the seconds until the key's window ends.
* **Window.** Fixed window per key, starting at its first counted attempt. Refused attempts are not counted, so hammering does not extend a lockout.
* **Memory and saturation.** At most `app.rate-limit.max-keys` keys (default 50 000) per counter. Expired entries are dropped first; then the
  least-recently-used entry that is **not** locked out. A locked-out entry is never evicted, so flooding with unique keys cannot lift a lockout;
  if every slot is an active lockout a NEW key is refused (fail closed) until the earliest lockout expires. The per-IP limit is checked first so
  one address can create at most 40 pair entries per window. A botnet with more addresses than `max-keys` can still exhaust capacity (it then
  gets 429s: the limiter fails closed, it does not switch itself off).
* **Per instance.** Counters live in the memory of one backend process: they are lost on restart and not shared. With N replicas the effective
  limit is up to N times the table above (k8s runs 2 backend replicas). This is a brake on online guessing from one place, **not** a global
  quota. Add an ingress limit (e.g. `nginx.ingress.kubernetes.io/limit-rps`) in front for a shared ceiling.
* **Observability.** Each refusal logs one structured line on logger `com.itsm.security.throttle`:
  `event=auth.throttled scope=<login-pair|login-ip|register-ip> clientIp=... emailToken=... retryAfterSeconds=...` and increments the
  `itsm.auth.throttled{scope}` counter. `emailToken` is an HMAC-SHA-256 of the email under a random per-process key (correlatable within one
  process, not reversible by hashing candidate addresses, different after restart and across replicas). Passwords, tokens and raw emails are
  never logged. Nothing is written to the audit table.
* **Tuning** (properties / env): `app.rate-limit.enabled` (`RATE_LIMIT_ENABLED`), `app.rate-limit.login-pair.max-failures|window`,
  `app.rate-limit.login-ip.max-failures|window`, `app.rate-limit.register-ip.max-attempts|window`, `app.rate-limit.max-keys`.

### Client address and proxies

Which address a limit is charged to is the most important deployment setting.

* **Default (`app.security.trusted-proxy-hops=0`)**: the TCP peer, `X-Forwarded-For` is ignored completely. Correct when clients reach the
  backend directly (the docker-compose stack).
* **Behind a proxy you operate** set `TRUSTED_PROXY_HOPS=N` (N = number of proxies, the ingress alone is 1) **and** `TRUSTED_PROXIES` (exact
  IPs or CIDR blocks of the proxy). Startup fails if hops > 0 and no proxy is named. `X-Forwarded-For` is read **only if the immediate TCP
  peer is inside `TRUSTED_PROXIES`**. Every proxy appends the address it saw to the END of the header, so what a client forged is on the
  left and the client is the **N-th entry from the right**: `X-Forwarded-For: forged, 203.0.113.9` with N=1 is `203.0.113.9`. Anything
  doubtful falls back to the immediate peer (untrusted peer, fewer than N entries, an entry that is not a plain IP literal: hostnames, ports,
  zone ids). Only the immediate peer is verified; with N>1 the intermediate hops are trusted because they sit between it and the client.
* **Kubernetes ingress (this repo).** Without the setting every user appears to come from the ingress pod and the limits become ONE limit for
  everyone (40 failed logins in 15 minutes would lock the whole company out). `infra/k8s/backend-configmap.yaml` and the Helm chart therefore
  set `TRUSTED_PROXY_HOPS=1` and `TRUSTED_PROXIES` to the RFC 1918 ranges as a working default. **Narrow `TRUSTED_PROXIES` to your ingress
  controller's pod CIDR and add a NetworkPolicy so that only the ingress can reach the backend**; otherwise any pod in the cluster could forge
  `X-Forwarded-For`. The ingress must **append** the client address (ingress-nginx does by default; if a CDN or load balancer sits in front of
  the ingress, set hops to 2 and make sure it overwrites rather than forwards a client-supplied header).
