import { test as base, expect } from '@playwright/test';

/** Run against a real backend instead of the in-browser fake: `E2E_LIVE_API=1 npm run test:e2e`. */
export const LIVE = process.env.E2E_LIVE_API === '1';
/** Must match NEXT_PUBLIC_API_URL baked into the build (default in src/lib/api.ts). */
export const API_URL = process.env.E2E_API_URL ?? 'http://localhost:8080';
export const DS_URL = `http://localhost:${process.env.E2E_DS_PORT ?? 3002}`;

export interface TestUser {
  name: string;
  email: string;
  password: string;
}

/** Unique per call so parallel tests (and repeated live runs) never collide. */
export const newUser = (label = 'user'): TestUser => ({
  name: 'E2E Tester',
  email: `e2e-${label}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.test`,
  password: 'Passw0rd!',
});

export interface ApiHelper {
  live: boolean;
  /** Create an account without going through the UI. */
  seed(user: TestUser): Promise<void>;
  /** Paths of every request the page made to the API (e.g. '/api/users/me'). */
  calls: string[];
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

export const test = base.extend<{ api: ApiHelper }>({
  // Installed for every test (auto) so route-guard tests can also assert "no API call was made".
  api: [
    async ({ page, request }, use) => {
      const calls: string[] = [];
      page.on('request', (r) => {
        if (r.url().startsWith(`${API_URL}/api/`)) calls.push(new URL(r.url()).pathname);
      });

      if (LIVE) {
        await use({
          live: true,
          calls,
          seed: async (u) => {
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
      const publicUser = ({ password: _p, ...rest }: MockUser) => ({ ...rest, role: 'USER' });

      await page.route(`${API_URL}/api/**`, async (route) => {
        const req = route.request();
        const path = new URL(req.url()).pathname;
        if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: CORS });

        if (path === '/api/auth/register' && req.method() === 'POST') {
          const b = req.postDataJSON() as TestUser;
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
        return route.fulfill(problem(404, 'RESOURCE_NOT_FOUND', 'Not found'));
      });

      await use({
        live: false,
        calls,
        seed: async (u) => {
          users.set(u.email, { ...u, id: nextId++, createdAt: new Date().toISOString() });
        },
      });
    },
    { auto: true },
  ],
});

export { expect };
