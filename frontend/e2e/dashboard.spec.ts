/** The dashboard's incident summary (mock services, per-account demo data). Account details are covered in auth.spec.ts. */
import type { Page } from '@playwright/test';
import { test, expect, newUser, type TestRole } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, signIn } from './helpers';

async function dashboard(page: Page, api: Parameters<typeof signIn>[1], role: TestRole) {
  await signIn(page, api, newUser(`dash-${role.toLowerCase()}`, role));
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: 'Dashboard', level: 1 })).toBeVisible();
}

const count = (page: Page, id: string) => page.getByTestId(`dash-count-${id}`);

test.describe('dashboard: incident summary', () => {
  test('an employee sees their groups with counts, what needs them first, and a way to report', async ({ page, api }) => {
    await dashboard(page, api, 'EMPLOYEE');
    await expect(page.getByRole('heading', { name: 'Your incidents', level: 2 })).toBeVisible();
    await expect(count(page, 'OPEN')).toHaveText(/^\d+$/);
    await expect(count(page, 'DONE')).toHaveText(/^\d+$/);
    const needs = page.getByRole('list', { name: 'Incidents that need you' });
    await expect(needs).toBeVisible();
    await expect(needs.getByRole('link').first()).toHaveAttribute('href', /\/incidents\/INC\d+/);
    await expect(page.getByRole('link', { name: 'Report an incident' })).toHaveAttribute('href', '/incidents/new');
    await expect(page.getByRole('main').getByRole('link', { name: 'Analytics' })).toHaveCount(0);
  });

  test('a technician sees their queue groups and no administration shortcuts', async ({ page, api }) => {
    await dashboard(page, api, 'TECHNICIAN');
    await expect(page.getByRole('heading', { name: 'Your queue', level: 2 })).toBeVisible();
    await expect(count(page, 'ACTIVE')).toHaveText(/^\d+$/);
    await expect(count(page, 'WAITING')).toHaveText(/^\d+$/);
    await expect(page.getByRole('main').getByRole('link', { name: 'Administration' })).toHaveCount(0);
  });

  test('an admin sees every group and the administration shortcuts', async ({ page, api }) => {
    await dashboard(page, api, 'ADMIN');
    await expect(page.getByRole('heading', { name: 'Incidents at a glance', level: 2 })).toBeVisible();
    await expect(count(page, 'UNASSIGNED')).toHaveText(/^\d+$/);
    await expect(page.getByRole('main').getByRole('link', { name: 'Analytics' })).toHaveAttribute('href', '/admin/analytics');
    await expect(page.getByRole('main').getByRole('link', { name: 'Administration' })).toHaveAttribute('href', '/admin');
  });

  test('a failure loading incidents is contained: the account card stays and Try again recovers', async ({ page, api }) => {
    await signIn(page, api, newUser('dash-fail', 'EMPLOYEE'));
    await page.evaluate(() => localStorage.setItem('itsm_p3_simulate_error', '1'));
    await page.goto('/dashboard');
    await expect(page.getByText('Your incidents could not be loaded right now.')).toBeVisible();
    await expect(page.getByText('Full name')).toBeVisible();
    await page.evaluate(() => localStorage.removeItem('itsm_p3_simulate_error'));
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(count(page, 'OPEN')).toBeVisible();
  });

  test('it passes axe and does not scroll sideways on a phone', async ({ page, api }) => {
    await dashboard(page, api, 'ADMIN');
    await expect(count(page, 'UNASSIGNED')).toBeVisible();
    expect(await axeViolations(page)).toEqual([]);
    await page.setViewportSize({ width: 390, height: 800 });
    await expectNoHorizontalOverflow(page);
  });
});
