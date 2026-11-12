import { test, expect } from '@playwright/test';
import { Seed } from './support/api';
import { admin } from './support/env';
import { openAdminAs } from './support/ui';
import { expectNo } from './support/safe';

/**
 * Assignment configuration against the real P1 backend, which has NO P3 strategy catalog deployed. That is the real ABSENT state:
 * the API must say so, writes must fail closed, and the UI must be read-only and say "not deployed" (never "no strategies").
 * These tests only read, so they never change the shared singleton and are order-independent.
 */

let seed: Seed;
test.beforeAll(async () => { seed = await Seed.asAdmin(admin().email, admin().password); });
test.afterAll(async () => { await seed.dispose(); });

test.describe('admin: assignment settings with the strategy catalog ABSENT', () => {
  test('the API reports ABSENT, offers no strategy list, and still returns the stored configuration', async () => {
    const r = await seed.admin.get('/api/admin/assignment-settings');
    expect(r.status).toBe(200);
    expect(r.body.strategyCatalogStatus).toBe('ABSENT');
    expect(r.body.availableStrategies, 'ABSENT must never be rendered as an empty list of strategies').toBeNull();
    expect(typeof r.body.activeStrategy).toBe('string');
    expect(r.body.defaultMaxWorkload).toBeGreaterThan(0);
    expect(Array.isArray(r.body.categoryOverrides)).toBe(true);
  });

  test('a write fails closed with 503 and a specific message, and changes nothing', async () => {
    const before = (await seed.admin.get('/api/admin/assignment-settings')).body;
    const put = await seed.admin.put('/api/admin/assignment-settings', {
      activeStrategy: before.activeStrategy, defaultMaxWorkload: before.defaultMaxWorkload + 1, categoryOverrides: [], version: before.version,
    });
    expect(put.status).toBe(503);
    expect(put.body.code).toBe('SERVICE_UNAVAILABLE');
    expectNo(JSON.stringify(put.body), /Exception|at com\./, 'the 503 body must not leak internals');
    const after = (await seed.admin.get('/api/admin/assignment-settings')).body;
    expect(after.version).toBe(before.version);
    expect(after.defaultMaxWorkload).toBe(before.defaultMaxWorkload);
  });

  test('the page says the catalog is not deployed, is read-only, and sends no write', async ({ page }) => {
    const writes: string[] = [];
    page.on('request', (r) => { if (r.url().includes('/api/admin/assignment-settings') && r.method() !== 'GET') writes.push(r.method()); });
    await openAdminAs(page, admin(), '/admin/assignment-settings');
    const note = page.getByTestId('catalog-unavailable');
    await expect(note).toHaveAttribute('data-catalog-status', 'ABSENT');
    await expect(note).toContainText('has not been deployed yet');
    await expect(note).not.toContainText('could not be reached');
    await expect(page.getByRole('combobox', { name: 'Active strategy' })).toBeDisabled();
    await expect(page.getByLabel('Default maximum workload')).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Add override' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Save settings' })).toBeDisabled();
    await expect(note.getByRole('button', { name: 'Try again' })).toHaveCount(0);
    expect(writes).toEqual([]);
  });

  test('the admin overview flags the missing catalog, and does not call it empty or unreachable', async ({ page }) => {
    await openAdminAs(page, admin(), '/admin');
    await expect(page.getByRole('link', { name: /Assignment strategy catalog not deployed yet/ })).toBeVisible();
    await expect(page.getByRole('link', { name: /could not be reached|No assignment strategies are registered/ })).toHaveCount(0);
  });
});
