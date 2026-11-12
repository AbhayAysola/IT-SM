import { test, expect } from '@playwright/test';
import { Seed } from './support/api';
import { admin } from './support/env';
import { openAdminAs } from './support/ui';
import { expectNo } from './support/safe';

/**
 * Assignment configuration against the real backend with P3's REAL strategy catalog (`RegisteredAssignmentStrategyCatalog`),
 * which registers exactly the strategies the assignment engine can run. The API must report the catalog AVAILABLE with those
 * keys, the strategy P1 seeds (LOAD_BASED) must be one of them, writes must be validated against them, and the UI must be editable.
 * These tests never change the shared singleton (the only write sends the stored values back, which is a no-op), so they stay
 * order-independent.
 */

const REGISTERED = ['LOAD_BASED', 'MANUAL', 'ROUND_ROBIN', 'SKILL_BASED'];

let seed: Seed;
test.beforeAll(async () => { seed = await Seed.asAdmin(admin().email, admin().password); });
test.afterAll(async () => { await seed.dispose(); });

test.describe('admin: assignment settings with the real strategy catalog', () => {
  test('the API reports AVAILABLE with exactly the strategies P3 registers, and LOAD_BASED is the seeded active one', async () => {
    const r = await seed.admin.get('/api/admin/assignment-settings');
    expect(r.status).toBe(200);
    expect(r.body.strategyCatalogStatus).toBe('AVAILABLE');
    expect(r.body.availableStrategies).toEqual(REGISTERED);
    expect(r.body.availableStrategies).toContain(r.body.activeStrategy);
    expect(r.body.activeStrategy).toBe('LOAD_BASED');
    expect(r.body.defaultMaxWorkload).toBeGreaterThan(0);
    expect(Array.isArray(r.body.categoryOverrides)).toBe(true);
  });

  test('a registered key is accepted (an unchanged update is a no-op), an unregistered one is refused with 400 and nothing changes', async () => {
    const before = (await seed.admin.get('/api/admin/assignment-settings')).body;
    const same = await seed.admin.put('/api/admin/assignment-settings', {
      activeStrategy: before.activeStrategy, defaultMaxWorkload: before.defaultMaxWorkload,
      categoryOverrides: before.categoryOverrides.map((o: any) => ({ categoryId: o.categoryId, strategy: o.strategy })), version: before.version,
    });
    expect(same.status).toBe(200);
    expect(same.body.activeStrategy).toBe('LOAD_BASED');
    expect(same.body.version).toBe(before.version);

    const bad = await seed.admin.put('/api/admin/assignment-settings', {
      activeStrategy: 'TELEPATHY', defaultMaxWorkload: before.defaultMaxWorkload, categoryOverrides: [], version: before.version,
    });
    expect(bad.status).toBe(400);
    expect(JSON.stringify(bad.body)).toContain('activeStrategy');
    expectNo(JSON.stringify(bad.body), /Exception|at com\./, 'the 400 body must not leak internals');
    const after = (await seed.admin.get('/api/admin/assignment-settings')).body;
    expect(after.version).toBe(before.version);
    expect(after.activeStrategy).toBe(before.activeStrategy);
  });

  test('the page is editable, offers exactly the registered strategies, and shows no catalog warning', async ({ page }) => {
    await openAdminAs(page, admin(), '/admin/assignment-settings');
    const select = page.getByRole('combobox', { name: 'Active strategy' });
    await expect(select).toBeEnabled();
    await expect(select).toContainText('LOAD_BASED');
    await expect(page.getByLabel('Default maximum workload')).toBeEnabled();
    await expect(page.getByRole('button', { name: 'Add override' })).toBeEnabled();
    await expect(page.getByTestId('catalog-unavailable')).toHaveCount(0);
    await select.click();
    await expect(page.getByRole('option')).toHaveText(REGISTERED);
    await page.keyboard.press('Escape');
  });

  test('the admin overview does not flag a missing or unreachable catalog', async ({ page }) => {
    await openAdminAs(page, admin(), '/admin');
    await expect(page.getByRole('heading', { name: /Administration|Admin/ }).first()).toBeVisible();
    await expect(page.getByRole('link', { name: /catalog not deployed yet|could not be reached|No assignment strategies are registered/ })).toHaveCount(0);
  });
});
