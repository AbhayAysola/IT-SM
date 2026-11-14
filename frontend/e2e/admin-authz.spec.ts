/**
 * Role-aware authorization for /admin. The frontend gate is UX only: the role comes from GET /api/users/me on every
 * visit, and the backend must enforce authorization on every admin API. Personas other than USER need backend
 * provisioning that does not exist yet, so in live mode (E2E_LIVE_API=1) those tests are skipped by `api.seed`.
 */
import type { Page } from '@playwright/test';
import { test, expect, newUser, newAdmin, API_URL, corsFor, type TestRole } from './fixtures';
import { signIn, trackErrors } from './helpers';

const ADMIN_ROUTES: Array<[path: string, heading: string]> = [
  ['/admin', 'Admin overview'],
  ['/admin/users', 'Users'],
  ['/admin/technicians', 'Technicians'],
  ['/admin/categories', 'Incident categories'],
  ['/admin/services', 'IT services'],
  ['/admin/sla-policies', 'SLA policies'],
  ['/admin/escalation-settings', 'Escalation settings'],
  ['/admin/analytics', 'Analytics'],
  ['/admin/assignment-settings', 'Assignment settings'],
  ['/admin/audit-logs', 'Audit logs'],
];

const adminNav = (page: Page) => page.getByRole('navigation', { name: 'Admin sections' });
const denied = (page: Page) => page.getByRole('heading', { name: 'Access denied', level: 1 });
const verifyFailed = (page: Page) => page.getByRole('heading', { name: "Couldn't verify your access", level: 1 });
const ME = `${API_URL}/api/users/me`;

/** Waits for the navbar's own /api/users/me answer so "the link is hidden" is not checked before the role arrives. */
async function signInAndWaitForRole(page: Page, api: Parameters<typeof signIn>[1], role: TestRole) {
  const me = page.waitForResponse((r) => r.url() === ME && r.request().method() === 'GET');
  // In live mode `signIn` skips personas the real backend cannot provision; the pending wait then ends with "Test ended", which
  // must not turn that skip into a failure.
  me.catch(() => {});
  await signIn(page, api, newUser(`authz-${role.toLowerCase()}`, role));
  await me;
}

test.describe('admin authorization: non-admin roles are denied', () => {
  for (const role of ['EMPLOYEE', 'TECHNICIAN', 'USER'] as const) {
    test(`${role} is denied on every admin route by direct navigation`, async ({ page, api }) => {
      const errors = trackErrors(page);
      await signIn(page, api, newUser(`deny-${role.toLowerCase()}`, role));
      for (const [path, heading] of ADMIN_ROUTES) {
        await page.goto(path);
        await expect(denied(page), path).toBeVisible();
        await expect(adminNav(page)).toHaveCount(0);
        await expect(page.getByRole('heading', { name: heading, level: 1, exact: true })).toHaveCount(0);
        expect(new URL(page.url()).pathname).toBe(path);
      }
      expect(errors).toEqual([]);
    });
  }

  test('the access-denied page offers a way back and a way out', async ({ page, api }) => {
    await signIn(page, api, newUser('deny-actions', 'EMPLOYEE'));
    await page.goto('/admin/users');
    await expect(denied(page)).toBeVisible();
    await page.getByRole('link', { name: 'Go to my dashboard' }).click();
    await page.waitForURL('**/dashboard');
    await page.goto('/admin/users');
    await page.getByRole('button', { name: 'Sign out' }).click();
    await page.waitForURL((u) => u.pathname === '/');
    expect(await page.evaluate(() => localStorage.getItem('it_sm_token'))).toBeNull();
  });
});

test.describe('admin authorization: ADMIN', () => {
  test('ADMIN can open all eight admin routes', async ({ page, api }) => {
    const errors = trackErrors(page);
    await signIn(page, api, newAdmin('authz-all'));
    for (const [path, heading] of ADMIN_ROUTES) {
      await page.goto(path);
      await expect(page.getByRole('heading', { name: heading, level: 1, exact: true }), path).toBeVisible();
      await expect(adminNav(page)).toBeVisible();
      await expect(denied(page)).toHaveCount(0);
    }
    expect(errors).toEqual([]);
  });

  test('admin content stays hidden until /api/users/me answers', async ({ page, api }) => {
    await signIn(page, api, newAdmin('authz-pending'));
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    await page.route(ME, async (route) => {
      await gate;
      await route.fallback();
    });
    await page.goto('/admin/users');
    await expect(page.getByText('Verifying authorization…')).toBeVisible();
    await expect(adminNav(page)).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Users', level: 1 })).toHaveCount(0);
    release();
    await expect(page.getByRole('heading', { name: 'Users', level: 1 })).toBeVisible();
  });
});

test.describe('admin authorization: Administration link', () => {
  for (const role of ['EMPLOYEE', 'TECHNICIAN', 'USER'] as const) {
    test(`${role} does not see the Administration link (desktop or mobile menu)`, async ({ page, api }) => {
      await signInAndWaitForRole(page, api, role);
      await expect(page.getByRole('link', { name: 'Administration' })).toHaveCount(0);
      await page.setViewportSize({ width: 390, height: 844 });
      await page.getByRole('button', { name: 'Account menu' }).click();
      await expect(page.getByRole('menu')).toBeVisible();
      await expect(page.getByRole('menuitem', { name: 'Administration' })).toHaveCount(0);
    });
  }

  test('ADMIN sees the Administration link once the backend confirms the role', async ({ page, api }) => {
    await signInAndWaitForRole(page, api, 'ADMIN');
    await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Administration' })).toBeVisible();
  });

  test('a forged ADMIN user left in localStorage does not reveal the link (the client keeps no user there any more)', async ({ page, api }) => {
    await signInAndWaitForRole(page, api, 'EMPLOYEE');
    await page.evaluate(() => {
      localStorage.setItem('it_sm_user', JSON.stringify({ id: 1, email: 'x@example.test', name: 'X', role: 'ADMIN', createdAt: '' }));
    });
    const me = page.waitForResponse((r) => r.url() === ME);
    await page.reload();
    await me;
    await expect(page.getByRole('link', { name: 'Administration' })).toHaveCount(0);
  });
});

test.describe('admin authorization: tampering and failures never grant access', () => {
  test('a forged ADMIN user in localStorage is ignored: the backend says EMPLOYEE', async ({ page, api }) => {
    await signIn(page, api, newUser('forged-role', 'EMPLOYEE'));
    await page.evaluate(() => {
      localStorage.setItem('it_sm_user', JSON.stringify({ id: 1, email: 'x@example.test', name: 'X', role: 'ADMIN', createdAt: '' }));
    });
    await page.goto('/admin');
    await expect(denied(page)).toBeVisible();
    await expect(adminNav(page)).toHaveCount(0);
  });

  test('a forged token and cached ADMIN user, with no backend session, are sent to sign-in', async ({ page, context, baseURL }) => {
    await context.addInitScript(() => {
      if (!sessionStorage.getItem('forged')) {
        sessionStorage.setItem('forged', '1');
        localStorage.setItem('it_sm_token', 'forged.token');
        localStorage.setItem('it_sm_user', JSON.stringify({ id: 1, email: 'x@example.test', name: 'X', role: 'ADMIN', createdAt: '' }));
      }
    });
    await page.goto(`${baseURL}/admin/users`);
    await page.waitForURL(/\/\?redirect=%2Fadmin%2Fusers/);
    await expect(adminNav(page)).toHaveCount(0);
    expect(await page.evaluate(() => localStorage.getItem('it_sm_token'))).toBeNull();
  });

  test('a 401 from /api/users/me ends the client session and redirects to sign-in', async ({ page, api }) => {
    await signIn(page, api, newAdmin('authz-401'));
    await page.route(ME, (route) =>
      route.fulfill({
        status: 401,
        headers: { ...corsFor(route.request().headers()['origin']), 'content-type': 'application/problem+json' },
        body: JSON.stringify({ status: 401, code: 'UNAUTHENTICATED', message: 'Authentication is required' }),
      }),
    );
    await page.goto('/admin/sla-policies');
    await page.waitForURL(/\/\?redirect=%2Fadmin%2Fsla-policies/);
    await expect(page.getByLabel('Email address')).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('it_sm_token'))).toBeNull();
  });

  test('a 403 from /api/users/me shows access denied', async ({ page, api }) => {
    await signIn(page, api, newAdmin('authz-403'));
    await page.route(ME, (route) =>
      route.fulfill({ status: 403, headers: corsFor(route.request().headers()['origin']), body: '{"message":"Forbidden"}' }),
    );
    await page.goto('/admin');
    await expect(denied(page)).toBeVisible();
    await expect(adminNav(page)).toHaveCount(0);
  });

  test('a backend 500 does not grant access, and retry recovers once it is back', async ({ page, api }) => {
    await signIn(page, api, newAdmin('authz-500'));
    await page.route(ME, (route) =>
      route.fulfill({ status: 500, headers: corsFor(route.request().headers()['origin']), body: '{"message":"boom"}' }),
    );
    await page.goto('/admin/users');
    await expect(verifyFailed(page)).toBeVisible();
    await expect(adminNav(page)).toHaveCount(0);
    await page.unroute(ME);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByRole('heading', { name: 'Users', level: 1 })).toBeVisible();
  });

  test('a network failure does not grant access', async ({ page, api }) => {
    await signIn(page, api, newAdmin('authz-net'));
    await page.route(ME, (route) => route.abort('internetdisconnected'));
    await page.goto('/admin/audit-logs');
    await expect(verifyFailed(page)).toBeVisible();
    await expect(adminNav(page)).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Audit logs', level: 1 })).toHaveCount(0);
  });

  test('a response without a role does not grant access', async ({ page, api }) => {
    await signIn(page, api, newAdmin('authz-norole'));
    await page.route(ME, (route) =>
      route.fulfill({
        status: 200,
        headers: { ...corsFor(route.request().headers()['origin']), 'content-type': 'application/json' },
        body: JSON.stringify({ id: 1, email: 'a@example.test', name: 'A', createdAt: '' }),
      }),
    );
    await page.goto('/admin');
    await expect(denied(page)).toBeVisible();
  });
});

test.describe('admin authorization: unauthenticated', () => {
  test('every admin route redirects to sign-in without calling the API', async ({ page, api }) => {
    for (const [path] of ADMIN_ROUTES) {
      await page.goto(path);
      await page.waitForURL((u) => u.pathname === '/' && u.searchParams.get('redirect') === path);
      await expect(adminNav(page)).toHaveCount(0);
    }
    // The only thing a signed-out visitor triggers is the guard's session check, which answers 401. No admin API is called.
    expect(new Set(api.calls)).toEqual(new Set(['/api/users/me']));
  });

  test('public registration sends no role and creates an account that cannot open /admin', async ({ page }) => {
    const u = newUser('authz-register');
    await page.goto('/');
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await expect(page.getByRole('combobox')).toHaveCount(0); // no role picker on the public form
    await page.getByLabel('Full name').fill(u.name);
    await page.getByLabel('Email address').fill(u.email);
    await page.getByLabel('Password').fill(u.password);
    const req = page.waitForRequest((r) => r.url() === `${API_URL}/api/auth/register` && r.method() === 'POST');
    await page.getByRole('button', { name: 'Create account' }).click();
    expect(Object.keys((await req).postDataJSON()).sort()).toEqual(['email', 'name', 'password']);
    await expect(page.getByRole('status')).toContainText(/sign in below/i);   // registration does not sign in
    await page.getByLabel('Password').fill(u.password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.waitForURL('**/dashboard');
    await page.goto('/admin');
    await expect(denied(page)).toBeVisible();
  });
});
