/**
 * P4's SLA facts on the incident list (mock services): the "Resolution due" column, the service-level quick filters with
 * their counts, and the nearest-deadline sort. Staff only; reporters keep the list as it was.
 */
import type { Page } from '@playwright/test';
import { test, expect, newUser, type TestRole } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, signIn } from './helpers';

async function open(page: Page, api: Parameters<typeof signIn>[1], role: TestRole) {
  await signIn(page, api, newUser(`sla-list-${role.toLowerCase()}`, role));
  await page.goto('/incidents');
  await page.getByRole('heading', { level: 1 }).first().waitFor();
}

const row = (page: Page, id: string) => page.getByRole('row').filter({ hasText: id });
const strip = (page: Page) => page.getByRole('group', { name: 'Service level attention' });
const quick = (page: Page, label: string) => strip(page).getByRole('button', { name: new RegExp(`^${label}`) });

test.describe('incident list: service level', () => {
  test('staff see each incident’s resolution deadline in words', async ({ page, api }) => {
    await open(page, api, 'ADMIN');
    await page.getByRole('tab', { name: /^In progress/ }).click();
    await expect(page.getByRole('columnheader', { name: 'Resolution due' })).toBeVisible();
    // The demo incidents are days old, so the P1 VPN outage is long past its 30-minute target.
    await expect(row(page, 'INC1002')).toContainText(/Due \d+ days ago/);
    await expect(row(page, 'INC1002')).toContainText('Breached');
  });

  test('the quick filters show their counts, apply the SLA filter, and switch off again', async ({ page, api }) => {
    await open(page, api, 'ADMIN');
    await page.getByRole('tab', { name: /^In progress/ }).click();
    await expect(quick(page, 'Breached')).toHaveText(/Breached\s*[1-9]\d*/);
    await expect(quick(page, 'Predicted to miss')).toHaveText(/Predicted to miss\s*0/, { timeout: 5000 });

    const breached = Number((await quick(page, 'Breached').innerText()).match(/\d+/)![0]);
    await quick(page, 'Breached').click();
    await expect(quick(page, 'Breached')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByRole('combobox', { name: 'SLA status' })).toContainText('Breached');
    const rows = page.getByRole('row').filter({ hasText: /INC\d{4}/ });
    await expect(rows, 'the count said how many the filter would show').toHaveCount(breached);
    for (const r of await rows.all()) await expect(r).toContainText('Breached');

    await quick(page, 'At risk').click(); // switches the SLA filter rather than adding to it
    await expect(quick(page, 'Breached')).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByRole('combobox', { name: 'SLA status' })).toContainText('At risk');
    await quick(page, 'At risk').click();
    await expect(page.getByRole('button', { name: 'Clear filters' })).toHaveCount(0);

    await quick(page, 'Predicted to miss').click(); // the demo data predicts nothing
    await expect(page.getByText('No incidents match your filters.')).toBeVisible();
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await expect(quick(page, 'Predicted to miss')).toHaveAttribute('aria-pressed', 'false');
    await expect(row(page, 'INC1002')).toBeVisible();
  });

  test('sorting by deadline and by priority are alternatives', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN');
    const deadline = page.getByRole('button', { name: 'Deadline' });
    const priority = page.getByRole('button', { name: 'Priority', exact: true });
    await deadline.click();
    await expect(deadline).toHaveAttribute('aria-pressed', 'true');
    await expect(priority).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByRole('row').filter({ hasText: /INC\d{4}/ }).first()).toContainText('Breached'); // overdue first
    await priority.click();
    await expect(priority).toHaveAttribute('aria-pressed', 'true');
    await expect(deadline).toHaveAttribute('aria-pressed', 'false');
  });

  test('reporters keep the list as it was', async ({ page, api }) => {
    await open(page, api, 'EMPLOYEE');
    await expect(page.getByRole('table')).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Resolution due' })).toHaveCount(0);
    await expect(strip(page)).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Deadline' })).toHaveCount(0);
  });

  test('is accessible and fits a phone', async ({ page, api }) => {
    await open(page, api, 'ADMIN');
    await page.getByRole('tab', { name: /^In progress/ }).click();
    await expect(strip(page)).toBeVisible();
    expect(await axeViolations(page)).toEqual([]);
    await page.setViewportSize({ width: 375, height: 800 });
    await expectNoHorizontalOverflow(page);
  });
});
