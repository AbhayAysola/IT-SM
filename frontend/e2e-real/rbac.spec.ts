import { test, expect } from '@playwright/test';
import { Api } from './support/api';
import { admin, employee, technician } from './support/env';
import { openAdminAs, uiLogin } from './support/ui';
import { expectNo } from './support/safe';

/** Unauthenticated, EMPLOYEE and TECHNICIAN against the real route guard and the real authorization rules. */

const ADMIN_API = [
  '/api/admin/users', '/api/admin/technicians', '/api/admin/categories', '/api/admin/services',
  '/api/admin/sla-policies', '/api/admin/assignment-settings', '/api/admin/audit-events',
];
const ADMIN_PAGES: Array<[string, string]> = [
  ['/admin', 'Admin overview'], ['/admin/users', 'Users'], ['/admin/technicians', 'Technicians'], ['/admin/categories', 'Categories'],
  ['/admin/services', 'Services'], ['/admin/sla-policies', 'SLA policies'], ['/admin/assignment-settings', 'Assignment settings'], ['/admin/audit-logs', 'Audit logs'],
];

test.describe('API authorization', () => {
  test('without a session every admin API is 401 with a problem body that leaks nothing', async () => {
    const anon = await Api.anonymous();
    for (const path of ADMIN_API) {
      const r = await anon.get(path);
      expect(r.status, path).toBe(401);
      expectNo(JSON.stringify(r.body), /Exception|at com\.|SELECT|stack/i, `${path} 401 body must not leak internals`);
    }
    await anon.dispose();
  });

  for (const [label, who] of [['EMPLOYEE', employee], ['TECHNICIAN', technician]] as const) {
    test(`${label}: every admin API is 403, reads and writes alike, and nothing is created`, async () => {
      const a = await Api.login(who().email, who().password);
      for (const path of ADMIN_API) expect((await a.get(path)).status, `GET ${path}`).toBe(403);
      const attempts: Array<[string, string, unknown]> = [
        ['POST', '/api/admin/users', { email: 'x@example.test', name: 'X', password: 'Aa1!xxxxxxxx', role: 'ADMIN' }],
        ['POST', '/api/admin/categories', { code: 'NOPE_CODE', name: 'Nope', keywords: ['nope'] }],
        ['POST', '/api/admin/services', { name: 'Nope service', businessCriticality: 3 }],
        ['PUT', '/api/admin/assignment-settings', {}],
        ['PUT', `/api/admin/users/${admin().id}/role`, { role: 'EMPLOYEE', version: 0 }],
        ['POST', `/api/admin/users/${admin().id}/deactivate`, { version: 0 }],
      ];
      for (const [m, path, body] of attempts) expect((await a.send(m, path, body)).status, `${m} ${path}`).toBe(403);
      await a.dispose();
      const adminApi = await Api.login(admin().email, admin().password);
      const found = await adminApi.get('/api/admin/users?search=x%40example.test');
      expect(found.body.totalItems, 'the refused create must not have happened').toBe(0);
      expect((await adminApi.get(`/api/admin/users/${admin().id}`)).body.role).toBe('ADMIN');
      await adminApi.dispose();
    });
  }

  test('a self-service profile call works for non-admins (authorization is per route, not a blanket block)', async () => {
    const a = await Api.login(employee().email, employee().password);
    const me = await a.get('/api/users/me');
    expect(me.status).toBe(200);
    expect(me.body.role).toBe('EMPLOYEE');
    expectNo(JSON.stringify(me.body), /password|hash|tokenVersion/i, 'the profile response must not carry credentials');
    await a.dispose();
  });
});

test.describe('route guard in the browser', () => {
  for (const [label, who] of [['EMPLOYEE', employee], ['TECHNICIAN', technician]] as const) {
    test(`${label} is denied every admin page, and sees no Administration link`, async ({ page }) => {
      await uiLogin(page, who());
      await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Administration' })).toHaveCount(0);
      for (const [path] of ADMIN_PAGES) {
        await page.goto(path);
        await expect(page.getByRole('heading', { name: 'Access denied', level: 1 }), path).toBeVisible();
        await expect(page.getByRole('navigation', { name: 'Admin sections' }), path).toHaveCount(0);
      }
    });
  }

  test('ADMIN reaches every admin page and sees the Administration link', async ({ page }) => {
    await uiLogin(page, admin());
    await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Administration' })).toBeVisible();
    for (const [path, heading] of ADMIN_PAGES) {
      await page.goto(path);
      await expect(page.getByRole('navigation', { name: 'Admin sections' }), path).toBeVisible();
      await expect(page.getByRole('heading', { level: 1, name: heading }), path).toBeVisible();
    }
  });

  test('a session that the server has ended is noticed on the next navigation', async ({ page }) => {
    await openAdminAs(page, admin(), '/admin');
    // end this browser's session from outside (a logout from another device revokes every session of the user)
    const other = await Api.login(admin().email, admin().password);
    expect((await other.post('/api/auth/logout')).status).toBe(200);
    await other.dispose();
    await page.goto('/admin/users');
    await page.waitForURL(/\/\?redirect=%2Fadmin%2Fusers/);
  });
});
