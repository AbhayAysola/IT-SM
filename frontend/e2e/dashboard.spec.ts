/** The role dashboards (mock services, per-account demo data). The SLA queue card has its own spec (sla-queue.spec.ts). */
import type { Page } from '@playwright/test';
import { test, expect, newUser, type TestRole } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, signIn } from './helpers';

async function dashboard(page: Page, api: Parameters<typeof signIn>[1], role: TestRole) {
  await signIn(page, api, newUser(`dash-${role.toLowerCase()}`, role));
}

const stat = (page: Page, id: string) => page.getByTestId(`dash-stat-${id}`);
const main = (page: Page) => page.getByRole('main');

test.describe('dashboard by role', () => {
  test('an employee sees their counts, what needs them first, and a way to report', async ({ page, api }) => {
    await dashboard(page, api, 'EMPLOYEE');
    await expect(stat(page, 'open')).toHaveText(/^\d+$/);
    await expect(stat(page, 'waiting-on-you')).toHaveText(/^\d+$/);
    await expect(stat(page, 'resolved')).toHaveText(/^\d+$/);
    const needs = main(page).getByRole('list', { name: 'Waiting on you' });
    await expect(needs).toBeVisible();
    await expect(needs.getByRole('link').first()).toHaveAttribute('href', /\/incidents\/INC\d+/);
    await expect(main(page).getByRole('link', { name: 'Report incident' })).toHaveAttribute('href', '/incidents/new');
    await expect(main(page).locator('a[href^="/admin"]')).toHaveCount(0);
  });

  test('a technician sees their workload and queue counts, and no administration links', async ({ page, api }) => {
    await dashboard(page, api, 'TECHNICIAN');
    await expect(main(page).getByText('Your workload', { exact: true })).toBeVisible();
    await expect(main(page).getByRole('meter', { name: 'Workload' })).toBeVisible();
    await expect(stat(page, 'active')).toHaveText(/^\d+$/);
    await expect(stat(page, 'waiting-on-reporter')).toHaveText(/^\d+$/);
    await expect(main(page).locator('a[href^="/admin"]')).toHaveCount(0);
  });

  test('an admin sees the incident overview, awaiting assignment and links into analytics', async ({ page, api }) => {
    await dashboard(page, api, 'ADMIN');
    await expect(stat(page, 'reported-30-days')).toHaveText(/^\d+$/);
    await expect(stat(page, 'sla-violations')).toHaveText(/^\d+$/);
    await expect(main(page).getByRole('list', { name: 'Awaiting assignment' }).or(main(page).getByText('Every incident has a technician.'))).toBeVisible();
    await expect(main(page).locator('a[href="/admin/analytics"]').first()).toBeVisible();
  });

  test('a failure loading incidents is contained: the page stays and Try again recovers', async ({ page, api }) => {
    await signIn(page, api, newUser('dash-fail', 'EMPLOYEE'));
    await page.evaluate(() => localStorage.setItem('itsm_p3_simulate_error', '1'));
    await page.goto('/dashboard');
    await expect(page.getByText('The incident service is unavailable. Please try again.')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: /^Welcome back/ })).toBeVisible();
    await page.evaluate(() => localStorage.removeItem('itsm_p3_simulate_error'));
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(stat(page, 'open')).toBeVisible();
  });

  test('it passes axe and does not scroll sideways on a phone', async ({ page, api }) => {
    await dashboard(page, api, 'ADMIN');
    await expect(stat(page, 'reported-30-days')).toBeVisible();
    expect(await axeViolations(page)).toEqual([]);
    await page.setViewportSize({ width: 390, height: 800 });
    await expectNoHorizontalOverflow(page);
  });
});
