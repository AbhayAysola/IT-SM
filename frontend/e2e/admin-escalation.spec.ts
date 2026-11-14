/** P4's escalation settings page against the fake admin API (the backend's EscalationIntegrationTest proves the real rules). */
import { test, expect } from './fixtures';
import { openAdmin, toast } from './admin-helpers';
import { axeViolations } from './helpers';

test.describe('admin: escalation settings', () => {
  const PATH = '/admin/escalation-settings';
  const unassigned = (page: import('@playwright/test').Page) => page.getByLabel('Unassigned for (minutes)');
  const nextLevel = (page: import('@playwright/test').Page) => page.getByLabel('Next level at (% of target)');
  const admins = (page: import('@playwright/test').Page) => page.getByLabel('Administrators at (% of target)');

  test('is reachable from the navigation and shows the current settings', async ({ page, api }) => {
    await openAdmin(page, api, '/admin');
    await page.getByRole('link', { name: 'Escalation' }).click();
    await expect(page).toHaveURL(new RegExp(`${PATH}$`));
    await expect(page.getByRole('heading', { name: 'Escalation settings', level: 1 })).toBeVisible();
    await expect(unassigned(page)).toHaveValue('30');
    await expect(nextLevel(page)).toHaveValue('150');
    await expect(admins(page)).toHaveValue('200');
    await expect(page.getByRole('button', { name: 'Save settings' })).toBeDisabled();
    expect(await axeViolations(page)).toEqual([]);
  });

  test('saves the three values with the version, and keeps them', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await unassigned(page).fill('15');
    await admins(page).fill('250');
    await page.getByRole('button', { name: 'Save settings' }).click();
    await toast(page, 'Escalation settings were saved.');
    expect(api.admin.last('PUT', '/api/admin/escalation-settings')!.body).toEqual({ unassignedMinutes: 15, secondLevelPct: 150, adminLevelPct: 250, version: 0 });
    await expect(page.getByRole('button', { name: 'Save settings' })).toBeDisabled();
    await page.reload();
    await expect(unassigned(page)).toHaveValue('15');
    await expect(admins(page)).toHaveValue('250');
  });

  test('checks the values before sending anything', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await unassigned(page).fill('0');
    await admins(page).fill('120');
    await page.getByRole('button', { name: 'Save settings' }).click();
    await expect(page.getByText('Enter a whole number of minutes from 1 to 10080 (one week).')).toBeVisible();
    await expect(page.getByText('Must be higher than the next-level percentage.')).toBeVisible();
    await expect(unassigned(page)).toHaveAttribute('aria-invalid', 'true');
    expect(api.admin.count('PUT', '/api/admin/escalation-settings')).toBe(0);
  });

  test('a change made elsewhere meanwhile can be kept on top of, or replaced by, the latest version', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    api.admin.escalation.version = 3; // someone else saved
    api.admin.escalation.unassignedMinutes = 45;
    await unassigned(page).fill('20');
    await page.getByRole('button', { name: 'Save settings' }).click();
    await expect(page.getByTestId('form-error')).toBeVisible();
    await page.getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await page.getByRole('button', { name: 'Save settings' }).click();
    await toast(page, 'Escalation settings were saved.');
    expect((api.admin.last('PUT', '/api/admin/escalation-settings')!.body as Record<string, unknown>).version).toBe(3);
    expect(api.admin.escalation.unassignedMinutes).toBe(20);
  });
});
