import { test, expect, type Page } from '@playwright/test';
import { Seed } from './support/api';
import { admin } from './support/env';
import { dialog, formError, openAdminAs, pick, rowMenu, rows, toast } from './support/ui';

/**
 * SLA policy configuration through the real UI and API. Global policies are shared singletons (one per priority), so every test
 * works on service-specific policies of a service it creates itself: it never touches shared state.
 */

let seed: Seed;
test.beforeAll(async () => { seed = await Seed.asAdmin(admin().email, admin().password); });
test.afterAll(async () => { await seed.dispose(); });

const policyRows = (page: Page, service: string) => rows(page).filter({ hasText: service });

test.describe('admin: SLA policies', () => {
  test('adding a service-specific policy validates the targets and stores exactly what was entered', async ({ page }) => {
    const svc = await seed.service('sla');
    await openAdminAs(page, admin(), '/admin/sla-policies');
    await page.getByRole('button', { name: 'Add policy' }).first().click();
    await pick(page, 'Applies to', svc.name);
    await pick(page, 'Priority', 'P2 High');
    await dialog(page).getByLabel('Response target (minutes)').fill('120');
    await dialog(page).getByLabel('Resolution target (minutes)').fill('60');
    await dialog(page).getByRole('button', { name: 'Add policy' }).click();
    await expect(dialog(page).getByText('Must be longer than the response target.')).toBeVisible();
    await dialog(page).getByLabel('Resolution target (minutes)').fill('480');
    await dialog(page).getByLabel('Warning threshold (%)').fill('100');
    await dialog(page).getByRole('button', { name: 'Add policy' }).click();
    await expect(dialog(page).getByText('Enter a whole number from 1 to 99.')).toBeVisible();
    await dialog(page).getByLabel('Warning threshold (%)').fill('75');
    await dialog(page).getByRole('button', { name: 'Add policy' }).click();
    await toast(page, 'P2 policy was added.');
    const list = (await seed.admin.get(`/api/admin/sla-policies?serviceId=${svc.id}`)).body.items;
    expect(list).toHaveLength(1);
    expect([list[0].priority, list[0].responseMinutes, list[0].resolutionMinutes, list[0].warningThresholdPct, list[0].active]).toEqual(['P2', 120, 480, 75, true]);
  });

  test('a second policy for the same service and priority is refused', async () => {
    const svc = await seed.service('sladup');
    const body = { serviceId: svc.id, priority: 'P3', responseMinutes: 30, resolutionMinutes: 240, warningThresholdPct: 80 };
    expect((await seed.admin.post('/api/admin/sla-policies', body)).status).toBe(201);
    const dup = await seed.admin.post('/api/admin/sla-policies', body);
    expect(dup.status).toBe(409);
    expect(dup.body.detail ?? dup.body.message).toMatch(/already exists/);
  });

  test('the effective-policy check reports the service policy, or falls back to the global one', async ({ page }) => {
    const svc = await seed.service('slaeff');
    const created = await seed.admin.post('/api/admin/sla-policies', { serviceId: svc.id, priority: 'P1', responseMinutes: 5, resolutionMinutes: 60, warningThresholdPct: 80 });
    expect(created.status).toBe(201);
    await openAdminAs(page, admin(), '/admin/sla-policies');
    const card = page.getByTestId('effective-sla');
    await pick(page, 'Service', svc.name);
    await pick(page, 'Priority', 'P1 Critical');
    await card.getByRole('button', { name: 'Check' }).click();
    await expect(page.getByTestId('effective-result')).toContainText('Service-specific policy');
    await expect(page.getByTestId('effective-result')).toContainText('respond within 5 min');
    // P3 has no policy for this service: the API answers with whatever global policy applies, the UI says which kind it is
    const api = (await seed.admin.get(`/api/admin/sla-policies/effective?serviceId=${svc.id}&priority=P4`));
    await pick(page, 'Priority', 'P4 Low');
    await card.getByRole('button', { name: 'Check' }).click();
    if (api.status === 200) await expect(page.getByTestId('effective-result')).toContainText(api.body.scope === 'GLOBAL' ? 'Global policy' : 'Service-specific policy');
    else await expect(page.getByTestId('effective-result')).toContainText('No policy applies');
  });

  test('optimistic lock on a policy edit, then deactivation removes it from the effective lookup', async ({ page }) => {
    const svc = await seed.service('slalock');
    const created = (await seed.admin.post('/api/admin/sla-policies', { serviceId: svc.id, priority: 'P1', responseMinutes: 10, resolutionMinutes: 100, warningThresholdPct: 80 })).body;
    await openAdminAs(page, admin(), '/admin/sla-policies');
    await page.getByRole('searchbox').fill(svc.name);
    await expect(policyRows(page, svc.name)).toHaveCount(1);
    await rowMenu(page, new RegExp(`Actions for ${svc.name}`));
    await page.getByRole('menuitem', { name: 'Edit targets' }).click();
    await expect(dialog(page).getByLabel('Applies to')).toBeDisabled();
    const other = await seed.admin.put(`/api/admin/sla-policies/${created.id}`, { responseMinutes: 15, resolutionMinutes: 150, warningThresholdPct: 80, version: created.version });
    expect(other.status).toBe(200);
    await dialog(page).getByLabel('Resolution target (minutes)').fill('90');
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(formError(page)).toContainText('changed by someone else');
    expect((await seed.admin.get(`/api/admin/sla-policies/${created.id}`)).body.resolutionMinutes, 'the stale save must not apply').toBe(150);
    await dialog(page).getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, 'P1 policy was updated.');
    expect((await seed.admin.get(`/api/admin/sla-policies/${created.id}`)).body.resolutionMinutes).toBe(90);

    await rowMenu(page, new RegExp(`Actions for ${svc.name}`));
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate policy' }).click();
    await toast(page, 'P1 policy was deactivated.');
    const eff = await seed.admin.get(`/api/admin/sla-policies/effective?serviceId=${svc.id}&priority=P1`);
    if (eff.status === 200) expect(eff.body.policyId, 'the deactivated policy must not be the effective one').not.toBe(created.id);
  });
});
