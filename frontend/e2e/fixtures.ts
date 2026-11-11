import { test as base, expect } from '@playwright/test';
import { AdminFake } from './admin-fake';

/** Run against a real backend instead of the in-browser fake: `E2E_LIVE_API=1 npm run test:e2e`. */
export const LIVE = process.env.E2E_LIVE_API === '1';
/** Must match NEXT_PUBLIC_API_URL baked into the build (default in src/lib/api.ts). */
export const API_URL = process.env.E2E_API_URL ?? 'http://localhost:8080';
export const DS_URL = `http://localhost:${process.env.E2E_DS_PORT ?? 3002}`;

/**
 * Role reported by GET /api/users/me. `USER` is the only role the real backend issues today; the others model the
 * agreed P1 roles so the frontend gate can be tested before backend provisioning exists.
 */
export type TestRole = 'USER' | 'EMPLOYEE' | 'TECHNICIAN' | 'ADMIN';

export interface TestUser {
  name: string;
  email: string;
  password: string;
  /** Defaults to `USER`, matching what the real backend issues. */
  role?: TestRole;
}

/** Unique per call so parallel tests (and repeated live runs) never collide. */
export const newUser = (label = 'user', role?: TestRole): TestUser => ({
  name: 'E2E Tester',
  email: `e2e-${label}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.test`,
  password: 'Passw0rd!',
  ...(role ? { role } : {}),
});

/** An ADMIN persona. In live mode, tests that seed one are skipped (see `seed`). */
export const newAdmin = (label = 'admin'): TestUser => newUser(label, 'ADMIN');

export interface ApiHelper {
  live: boolean;
  /**
   * Create an account without going through the UI. Live mode can only create what public registration creates;
   * seeding any other role there skips the test instead of faking a privileged account.
   */
  seed(user: TestUser): Promise<void>;
  /** Paths of every request the page made to the API (e.g. '/api/users/me'). */
  calls: string[];
  /** Fake of the backend audit API (`/api/admin/audit-events`). Hermetic mode only. */
  audit: AuditFake;
  /**
   * Ends the user's session on the server without the browser doing anything, as an expiry or a logout from another device would.
   * Hermetic: forgets the fake's session. Live: signs in with a Bearer token and calls logout (which revokes ALL of that user's sessions).
   */
  expireSession(user: TestUser): Promise<void>;
  /** Hermetic mode only: what the fake saw. `logouts` counts POST /api/auth/logout calls; `loginModes` the X-ITSM-Client of each login. */
  sessionLog: { logouts: number; loginModes: Array<string | undefined>; csrfRejected: number };
  /** Hermetic mode only: set `authThrottle.on = true` and the fake answers login and register with 429 + Retry-After. */
  authThrottle: { on: boolean };
  /** Stateful fake of the other admin APIs (users, technicians, categories, services, SLA, assignment). Hermetic mode only. */
  admin: AdminFake;
}

export interface FakeAuditEvent {
  id: string;
  occurredAt: string;
  actor: { type: 'USER' | 'SYSTEM'; id: number | null; role: string | null; name: string | null };
  action: string;
  entityType: string;
  entityId: string | null;
  incidentId: string | null;
  previousState: string | null;
  newState: string | null;
  description: string;
  metadata: Record<string, unknown>;
}

export interface AuditFake {
  /** Rows the fake serves. Replace freely inside a test. */
  events: FakeAuditEvent[];
  /** Query strings of every list request, in order. */
  requests: URLSearchParams[];
  /** Paths of every detail request. */
  detailRequests: string[];
  /** When set, the next `times` audit requests answer this status (problem+json) instead of data. */
  fail: { status: number; code: string; detail: string; times: number } | null;
}

/** `n` deterministic events, newest first by id: `evt-1` is the newest. */
export function makeAuditEvents(n: number): FakeAuditEvent[] {
  const base = Date.parse('2026-11-05T12:00:00Z');
  return Array.from({ length: n }, (_, i) => ({
    id: `00000000-0000-4000-8000-${String(i + 1).padStart(12, '0')}`,
    occurredAt: new Date(base - i * 3_600_000).toISOString(),
    actor: i % 5 === 4
      ? { type: 'SYSTEM' as const, id: null, role: null, name: 'SLA_MONITOR' }
      : { type: 'USER' as const, id: 10 + (i % 3), role: i % 3 === 0 ? 'ADMIN' : 'TECHNICIAN', name: null },
    action: i % 2 === 0 ? 'USER_CREATED' : 'SLA_POLICY_UPDATED',
    entityType: i % 2 === 0 ? 'USER' : 'SLA_POLICY',
    entityId: String(100 + i),
    incidentId: null,
    previousState: null,
    newState: null,
    description: `Event number ${i + 1}`,
    metadata: i === 0 ? { changedFields: ['name', 'code'], count: 3, flag: true, note: '<img src=x onerror=alert(1)>' } : {},
  }));
}

interface MockUser extends TestUser {
  id: number;
  createdAt: string;
}

/** The backend allows credentials for exact origins only, never `*`: the fake echoes the caller's origin and sets allow-credentials. */
export const corsFor = (origin: string | undefined) => ({
  'access-control-allow-origin': origin ?? 'http://localhost:3000',
  'access-control-allow-credentials': 'true',
  'access-control-allow-headers': 'content-type, x-itsm-client',
  'access-control-allow-methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
  vary: 'Origin',
});

/** Applies the documented filter/sort/paging semantics to `events` (the real ones are tested in the backend suite). */
function auditPage(events: FakeAuditEvent[], q: URLSearchParams) {
  const eq = (v: string | null, f: string | null) => !f || v === f;
  const from = q.get('from'), to = q.get('to');
  const rows = events.filter((e) =>
    eq(e.action, q.get('action')) && eq(e.entityType, q.get('entityType')) && eq(e.entityId, q.get('entityId')) &&
    eq(e.actor.role, q.get('actorRole')) && eq(e.actor.type, q.get('actorType')) &&
    (!q.get('actorId') || String(e.actor.id) === q.get('actorId')) &&
    (!from || Date.parse(e.occurredAt) >= Date.parse(from)) && (!to || Date.parse(e.occurredAt) < Date.parse(to)));
  const sort = q.get('sort') ?? 'occurredAt';
  const m = q.get('dir') === 'asc' ? 1 : -1;
  const key = (e: FakeAuditEvent): string => (sort === 'actorRole' ? e.actor.role ?? '' : sort === 'action' ? e.action : sort === 'entityType' ? e.entityType : e.occurredAt);
  rows.sort((a, b) => key(a).localeCompare(key(b)) * m || a.id.localeCompare(b.id) * m);
  const size = Number(q.get('size') ?? 20), page = Number(q.get('page') ?? 0);
  return { items: rows.slice(page * size, page * size + size), page, size, totalItems: rows.length, totalPages: Math.ceil(rows.length / size) };
}

export const test = base.extend<{ api: ApiHelper }>({
  // Installed for every test (auto) so route-guard tests can also assert "no API call was made".
  api: [
    async ({ page, request }, use, testInfo) => {
      const calls: string[] = [];
      page.on('request', (r) => {
        if (r.url().startsWith(`${API_URL}/api/`)) calls.push(new URL(r.url()).pathname);
      });

      const audit: AuditFake = { events: makeAuditEvents(25), requests: [], detailRequests: [], fail: null };
      const admin = new AdminFake();
      const throttle = { on: false };
      const sessionLog = { logouts: 0, loginModes: [] as Array<string | undefined>, csrfRejected: 0 };
      // The fake's stand-in for the HttpOnly session cookie (a browser cookie set by a fulfilled route is not reliable): who is signed in.
      let sessionEmail: string | null = null;
      let CORS = corsFor(undefined);

      if (LIVE) {
        await use({
          live: true,
          calls,
          audit,
          admin,
          authThrottle: { on: false },
          sessionLog: { logouts: 0, loginModes: [], csrfRejected: 0 },
          expireSession: async (u) => {
            // A Bearer login (no X-ITSM-Client header) returns a token; logout with it revokes every session of that user.
            const login = await request.post(`${API_URL}/api/auth/login`, { data: { email: u.email, password: u.password } });
            expect(login.ok(), `bearer login failed: ${login.status()}`).toBeTruthy();
            const { token } = await login.json();
            const out = await request.post(`${API_URL}/api/auth/logout`, { headers: { Authorization: `Bearer ${token}` } });
            expect(out.ok(), `logout failed: ${out.status()}`).toBeTruthy();
          },
          seed: async (u) => {
            testInfo.skip(
              !!u.role && u.role !== 'USER',
              `Live backend cannot provision ${u.role} accounts yet (pending P1 backend bootstrap/user management).`,
            );
            const res = await request.post(`${API_URL}/api/auth/register`, {
              data: { email: u.email, password: u.password, name: u.name },
            });
            expect(res.ok(), `seed register failed: ${res.status()}`).toBeTruthy();
          },
        });
        return;
      }

      // Hermetic fake of the three endpoints the frontend uses. Mirrors the backend's response shapes,
      // including RFC 7807 problem+json errors with `code` and the legacy `message` alias.
      const users = new Map<string, MockUser>();
      let nextId = 1;
      const problem = (status: number, code: string, detail: string) => ({
        status,
        headers: { ...CORS, 'content-type': 'application/problem+json' },
        body: JSON.stringify({
          type: `urn:itsm:error:${code.toLowerCase().replace(/_/g, '-')}`,
          title: 'Error', status, detail, code, message: detail, traceId: 'mock', timestamp: new Date().toISOString(),
        }),
      });
      const ok = (body: unknown) => ({ status: 200, headers: { ...CORS, 'content-type': 'application/json' }, body: JSON.stringify(body) });
      const publicUser = ({ password: _p, role, ...rest }: MockUser) => ({ ...rest, role: role ?? 'USER' });

      await page.route(`${API_URL}/api/**`, async (route) => {
        const req = route.request();
        const path = new URL(req.url()).pathname;
        CORS = corsFor(req.headers()['origin']);
        if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });
        // Like the backend: an unsafe request made with the session cookie needs the web-client header (and the browser's own Origin).
        const unsafe = !['GET', 'HEAD', 'OPTIONS'].includes(req.method());
        const isLoginOrRegister = path === '/api/auth/login' || path === '/api/auth/register';
        if (unsafe && !isLoginOrRegister && sessionEmail && (req.headers()['x-itsm-client'] !== 'web' || !req.headers()['origin'])) {
          sessionLog.csrfRejected++;
          return route.fulfill(problem(403, 'FORBIDDEN', 'Request rejected'));
        }
        const caller = () => (sessionEmail ? users.get(sessionEmail) : undefined);


        if ((path === '/api/auth/register' || path === '/api/auth/login') && req.method() === 'POST' && throttle.on) {
          const r = problem(429, 'RATE_LIMITED', 'Too many attempts. Please try again later.');
          return route.fulfill({ ...r, headers: { ...r.headers, 'retry-after': '900' } });
        }
        if (path === '/api/auth/register' && req.method() === 'POST') {
          // Like the real backend: public registration ignores any role in the body, and a new and an already registered
          // address get the SAME 202 {status:'ACCEPTED'} (no token, no duplicate signal). An existing account is left untouched.
          const { role: _ignored, ...b } = req.postDataJSON() as TestUser;
          if (!users.has(b.email)) users.set(b.email, { ...b, id: nextId++, createdAt: new Date().toISOString() });
          return route.fulfill({ status: 202, headers: { ...CORS, 'content-type': 'application/json' }, body: JSON.stringify({ status: 'ACCEPTED' }) });
        }
        if (path === '/api/auth/login' && req.method() === 'POST') {
          // Browser mode only (`X-ITSM-Client: web`): the session is a cookie and the body is `{user}`, never a token.
          const mode = req.headers()['x-itsm-client'];
          sessionLog.loginModes.push(mode);
          if (mode !== 'web') return route.fulfill(problem(400, 'INVALID_INPUT', 'Invalid client mode'));
          const b = req.postDataJSON() as TestUser;
          const u = users.get(b.email);
          if (!u || u.password !== b.password) return route.fulfill(problem(401, 'INVALID_CREDENTIALS', 'Invalid email or password'));
          sessionEmail = u.email;
          return route.fulfill(ok({ user: publicUser(u) }));
        }
        if (path === '/api/auth/logout' && req.method() === 'POST') {
          if (!caller()) return route.fulfill(problem(401, 'UNAUTHENTICATED', 'Authentication is required'));
          sessionLog.logouts++;
          sessionEmail = null;
          return route.fulfill(ok({ status: 'LOGGED_OUT' }));
        }
        if (path === '/api/users/me' && req.method() === 'GET') {
          const u = caller();
          return u ? route.fulfill(ok(publicUser(u))) : route.fulfill(problem(401, 'UNAUTHENTICATED', 'Authentication is required'));
        }
        if (path === '/api/admin/audit-events' || path.startsWith('/api/admin/audit-events/')) {
          // Like the real endpoint: 401 without a valid token, 403 unless the caller is an ADMIN, GET only.
          const who = caller();
          if (!who) return route.fulfill(problem(401, 'UNAUTHENTICATED', 'Authentication is required'));
          if (who.role !== 'ADMIN') return route.fulfill(problem(403, 'FORBIDDEN', 'Operation not permitted'));
          if (req.method() !== 'GET') return route.fulfill(problem(405, 'METHOD_NOT_ALLOWED', 'Method not allowed'));
          if (audit.fail && audit.fail.times > 0) {
            audit.fail.times -= 1;
            return route.fulfill(problem(audit.fail.status, audit.fail.code, audit.fail.detail));
          }
          const url = new URL(req.url());
          if (path !== '/api/admin/audit-events') {
            audit.detailRequests.push(path);
            const found = audit.events.find((e) => e.id === decodeURIComponent(path.split('/').pop()!));
            return found ? route.fulfill(ok(found)) : route.fulfill(problem(404, 'RESOURCE_NOT_FOUND', 'Audit event was not found'));
          }
          audit.requests.push(url.searchParams);
          return route.fulfill(ok(auditPage(audit.events, url.searchParams)));
        }
        if (path.startsWith('/api/admin/')) {
          // Every admin API: 401 without a valid token, 403 unless the caller is an ADMIN, then the stateful fake.
          const who = caller();
          if (!who) return route.fulfill(problem(401, 'UNAUTHENTICATED', 'Authentication is required'));
          if (who.role !== 'ADMIN') return route.fulfill(problem(403, 'FORBIDDEN', 'Operation not permitted'));
          admin.selfEmail = who.email;
          const url = new URL(req.url());
          const out = admin.handle({ method: req.method(), path, query: url.searchParams, body: req.postData() ? req.postDataJSON() : undefined });
          return route.fulfill({ status: out.status, headers: { ...CORS, 'content-type': out.status >= 400 ? 'application/problem+json' : 'application/json' }, body: out.body === undefined ? undefined : JSON.stringify(out.body) });
        }
        return route.fulfill(problem(404, 'RESOURCE_NOT_FOUND', 'Not found'));
      });

      await use({
        live: false,
        calls,
        audit,
        admin,
        authThrottle: throttle,
        sessionLog,
        expireSession: async () => {
          sessionEmail = null;
        },
        seed: async (u) => {
          users.set(u.email, { ...u, id: nextId++, createdAt: new Date().toISOString() });
        },
      });
    },
    { auto: true },
  ],
});

export { expect };
