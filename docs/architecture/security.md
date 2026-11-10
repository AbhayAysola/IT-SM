# Authentication, roles and user administration (P1, Batch 5)

## Roles and permissions

Exactly three roles, stored in `users.role` (CHECK-constrained): `EMPLOYEE`, `TECHNICIAN`, `ADMIN`. There is no `USER` role;
migration `V15` mapped legacy `USER` rows to `EMPLOYEE`, and `Role.fromStored` still maps a stray `USER` for safety.
The permission model (`RolePermissions`) is unchanged: EMPLOYEE reports and follows its own incidents, TECHNICIAN works
assigned incidents, ADMIN holds every permission (`USER_MANAGE`, `CATALOG_MANAGE`, `SLA_CONFIGURE`, ...).

## Public registration

`POST /api/auth/register {email, password, name}` ALWAYS creates an `EMPLOYEE`. The DTO has no role field, and unknown JSON
properties (`role`, `roles`, `active`, ...) are ignored. Email is trimmed and lower-cased; uniqueness is case-insensitive
(DB unique index on `lower(email)` plus an application check; a race gives 409). Password: 8-128 chars and at most 72 UTF-8
bytes (BCrypt truncates silently beyond that, so longer input is rejected). Returns `200 {token, user}` (unchanged shape,
`user` gained `active`). The response never contains the hash. Self-registration is audited (`USER_REGISTERED`).

## Login

`POST /api/auth/login`. Unknown email, wrong password, inactive account and over-long password all return the same
`401 INVALID_CREDENTIALS "Invalid email or password"` (no account enumeration, no disabled-account oracle). Spring's
`DaoAuthenticationProvider` spends BCrypt time even for unknown users, which evens out timing.

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

## Audit

Actions (`entityType USER`, actor = the admin, `targetUserId` in metadata): `USER_CREATED`, `USER_UPDATED`, `USER_ROLE_CHANGED`
(previous/new state = role), `USER_ACTIVATED`/`USER_DEACTIVATED` (INACTIVE/ACTIVE), `USER_PASSWORD_RESET`,
`USER_SESSIONS_REVOKED`, `USER_REGISTERED` (actor = the new user), `USER_ADMIN_BOOTSTRAPPED` (system actor `ADMIN_BOOTSTRAP`).
Rows are written through `SpringAuditPublisher` inside the mutation's transaction. Personal data (email, name) and every
secret stay out of metadata: `USER_UPDATED` records field names only.

**Failed logins are NOT audited yet.** `SpringAuditPublisher` is `MANDATORY` (needs a business transaction) and a login
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

## Refresh tokens: status and plan

**Not implemented in B5, on purpose.** The merged frontend stores the access token in `localStorage` plus a JS-readable
cookie and sends it as a Bearer header; a backend-only switch to HttpOnly cookies would break it. The access-token protocol is
unchanged apart from the token contents. Proposed contract for a coordinated change (needs the frontend and a migration number,
`V16` is the B6 technician-profile migration, so refresh tokens start at `V17`):

1. `POST /api/auth/login|register` -> JSON `{accessToken (15 min), user}` + `Set-Cookie: refresh=...; HttpOnly; Secure;
   SameSite=Strict; Path=/api/auth`. Access token kept in memory only.
2. `POST /api/auth/refresh` (cookie) rotates the refresh token; `POST /api/auth/logout` revokes it. Both require a CSRF
   header (double-submit) and an `Origin` allow-list check; CSRF protection must then be enabled for those paths.
3. Table `refresh_tokens(id, user_id, token_hash, family_id, expires_at, revoked_at, replaced_by, created_at)`; only SHA-256
   hashes stored; presenting an already-rotated token revokes its whole family (reuse detection); deactivation, role change,
   password reset and revoke-sessions revoke all families of the user.
4. Frontend: replace `lib/auth.ts` storage, add a silent-refresh interceptor in `lib/api.ts`, drop the cached role.

## Other hardening and known limitations

* **Rate limiting: none.** Login/register can be brute-forced or used for credential stuffing. No distributed limiter was
  added (no Redis for this batch). Put a rate limit in front (ingress / API gateway), or add an in-process limiter as a stop-gap.
  There is no account lockout (the old draft's `failed_logins`/`locked_until` were dropped).
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
