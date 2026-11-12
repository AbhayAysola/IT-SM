import { test, expect } from '@playwright/test';
import { Api, Seed, randomPassword } from './support/api';
import { admin, employee, technician } from './support/env';
import { openAdminAs, rows } from './support/ui';
import { expectNo, JWT, BCRYPT } from './support/safe';

/** Audit retrieval through the real API and UI, and what an audit record must never contain. */

let seed: Seed;
test.beforeAll(async () => { seed = await Seed.asAdmin(admin().email, admin().password); });
test.afterAll(async () => { await seed.dispose(); });

const eventsFor = async (entityType: string, entityId: number | string) =>
  (await seed.admin.get(`/api/admin/audit-events?entityType=${entityType}&entityId=${entityId}&size=50`)).body;

test.describe('admin: audit', () => {
  test('an administrative action leaves one attributable, persisted event that the API returns', async () => {
    const cat = await seed.category('AUD');
    const page = await eventsFor('CATEGORY', cat.id);
    expect(page.totalItems).toBeGreaterThanOrEqual(1);
    const ev = page.items.find((e: any) => /CREATED/.test(e.action));
    expect(ev, 'a *_CREATED event for the new category').toBeTruthy();
    expect(ev.actor).toMatchObject({ type: 'USER', id: admin().id, role: 'ADMIN' });
    expect(ev.entityId).toBe(String(cat.id));
    expect(ev.occurredAt).toBeTruthy();
    const detail = await seed.admin.get(`/api/admin/audit-events/${ev.id}`);
    expect(detail.status).toBe(200);
    expect(detail.body.id).toBe(ev.id);
  });

  test('privacy: no password, hash, token or cookie reaches an audit event, whatever the action', async () => {
    const u = await seed.user('audpriv');
    const secret = randomPassword();
    expect((await seed.admin.post(`/api/admin/users/${u.id}/reset-password`, { password: secret })).status).toBe(200);
    expect((await seed.admin.post(`/api/admin/users/${u.id}/revoke-sessions`)).status).toBe(200);
    const { items } = await eventsFor('USER', u.id);
    expect(items.length).toBeGreaterThanOrEqual(3);              // created, password reset, sessions revoked
    const text = JSON.stringify(items);
    expectNo(text, secret, 'the reset password must not be in any audit event');
    expectNo(text, u.password, 'the initial password must not be in any audit event');
    expectNo(text, BCRYPT, 'no password hash in any audit event');
    expectNo(text, JWT, 'no JWT in any audit event');
    for (const e of items) for (const key of Object.keys(e.metadata ?? {})) expect(key, `metadata key on ${e.action}`).not.toMatch(/pass|hash|token|secret|cookie|credential/i);
  });

  test('retrieval is admin-only: EMPLOYEE and TECHNICIAN are refused, an anonymous caller is unauthenticated', async () => {
    for (const who of [employee, technician]) {
      const a = await Api.login(who().email, who().password);
      expect((await a.get('/api/admin/audit-events')).status).toBe(403);
      await a.dispose();
    }
    const anon = await Api.anonymous();
    expect((await anon.get('/api/admin/audit-events')).status).toBe(401);
    await anon.dispose();
  });

  test('the audit trail is read-only over HTTP', async () => {
    const list = (await seed.admin.get('/api/admin/audit-events?size=1')).body;
    const id = list.items[0].id;
    for (const [m, p] of [['POST', '/api/admin/audit-events'], ['PUT', `/api/admin/audit-events/${id}`], ['DELETE', `/api/admin/audit-events/${id}`], ['PATCH', `/api/admin/audit-events/${id}`]] as const) {
      const r = await seed.admin.send(m, p, {});
      expect([403, 404, 405], `${m} ${p}`).toContain(r.status);
    }
    expect((await seed.admin.get(`/api/admin/audit-events/${id}`)).status).toBe(200);
  });

  test('paging parameters are validated: an offset beyond the supported range is a 400, never a 500', async () => {
    // [query, expected error code]. In-range numbers that make an offset too large are rejected by the paging guard (INVALID_INPUT);
    // a number that does not even fit an Int never reaches it and is a malformed request. Both are generic 400s.
    const cases: Array<[string, string]> = [
      ['page=2147483647&size=100', 'INVALID_INPUT'], ['page=99999999999&size=10', 'MALFORMED_REQUEST'],
      ['page=-1', 'INVALID_INPUT'], ['size=0', 'INVALID_INPUT'], ['size=100000', 'INVALID_INPUT'],
    ];
    for (const [q, code] of cases) {
      const r = await seed.admin.get(`/api/admin/audit-events?${q}`);
      expect(r.status, q).toBe(400);
      expect(r.body.code, q).toBe(code);
      expectNo(JSON.stringify(r.body), /Exception|at com\.|NumberFormat/, `${q}: the 400 body must not leak internals`);
    }
    const ok = await seed.admin.get('/api/admin/audit-events?page=0&size=1');
    expect(ok.status).toBe(200);
  });

  test('the audit page lists the event for an entity, filtered through the real API', async ({ page }) => {
    const cat = await seed.category('AUDUI');
    await openAdminAs(page, admin(), '/admin/audit-logs');
    await page.getByLabel('Entity type', { exact: true }).fill('category');
    await page.getByLabel('Entity ID', { exact: true }).fill(String(cat.id));
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText(/created/i);
    expectNo(await page.locator('main').innerText(), /\$2[aby]\$|eyJ/, 'the audit page must show no hash or token');
  });

  test('the overview shows the persisted recent changes', async ({ page }) => {
    await openAdminAs(page, admin(), '/admin');
    await expect(page.getByTestId('recent-changes').getByRole('listitem').first()).toBeVisible();
  });
});
