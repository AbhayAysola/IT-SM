import { test as base, expect } from '@playwright/test';

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

const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-headers': 'authorization, content-type',
  'access-control-allow-methods': 'GET,POST,PUT,PATCH,DELETE,OPTIONS',
};

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

      if (LIVE) {
        await use({
          live: true,
          calls,
          audit,
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
        if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });

        if (path === '/api/auth/register' && req.method() === 'POST') {
          // Like the real backend, public registration ignores any role in the body.
          const { role: _ignored, ...b } = req.postDataJSON() as TestUser;
          if (users.has(b.email)) return route.fulfill(problem(409, 'DUPLICATE_RESOURCE', 'Email is already registered'));
          const u: MockUser = { ...b, id: nextId++, createdAt: new Date().toISOString() };
          users.set(u.email, u);
          return route.fulfill(ok({ token: `mock.${u.email}`, user: publicUser(u) }));
        }
        if (path === '/api/auth/login' && req.method() === 'POST') {
          const b = req.postDataJSON() as TestUser;
          const u = users.get(b.email);
          if (!u || u.password !== b.password) return route.fulfill(problem(401, 'INVALID_CREDENTIALS', 'Invalid email or password'));
          return route.fulfill(ok({ token: `mock.${u.email}`, user: publicUser(u) }));
        }
        if (path === '/api/users/me' && req.method() === 'GET') {
          const token = (req.headers()['authorization'] ?? '').replace('Bearer ', '');
          const u = token.startsWith('mock.') ? users.get(token.slice(5)) : undefined;
          return u ? route.fulfill(ok(publicUser(u))) : route.fulfill(problem(401, 'UNAUTHENTICATED', 'Authentication is required'));
        }
        if (path === '/api/admin/audit-events' || path.startsWith('/api/admin/audit-events/')) {
          // Like the real endpoint: 401 without a valid token, 403 unless the caller is an ADMIN, GET only.
          const token = (req.headers()['authorization'] ?? '').replace('Bearer ', '');
          const caller = token.startsWith('mock.') ? users.get(token.slice(5)) : undefined;
          if (!caller) return route.fulfill(problem(401, 'UNAUTHENTICATED', 'Authentication is required'));
          if (caller.role !== 'ADMIN') return route.fulfill(problem(403, 'FORBIDDEN', 'Operation not permitted'));
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
        return route.fulfill(problem(404, 'RESOURCE_NOT_FOUND', 'Not found'));
      });

      await use({
        live: false,
        calls,
        audit,
        seed: async (u) => {
          users.set(u.email, { ...u, id: nextId++, createdAt: new Date().toISOString() });
        },
      });
    },
    { auto: true },
  ],
});

export { expect };
