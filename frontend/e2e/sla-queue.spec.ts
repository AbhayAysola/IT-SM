/**
 * P4's SLA queue on the dashboard (mock services): staff see the open incidents closest to their resolution deadline,
 * breached first; reporters get their own dashboard without it. The ordering and the live mapper are pure and tested last.
 */
import type { Page } from '@playwright/test';
import { test, expect, newUser, type TestRole } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, signIn } from './helpers';
import { queueOrder, type SlaQueueItem } from '../src/features/incidents/model/sla';
import { toQueueItem } from '../src/features/incidents/services/incident-mappers';

async function dashboard(page: Page, api: Parameters<typeof signIn>[1], role: TestRole) {
  await signIn(page, api, newUser(`queue-${role.toLowerCase()}`, role));
}

const queue = (page: Page) => page.getByTestId('sla-queue');

test.describe('SLA queue on the dashboard', () => {
  test('a technician sees their own open incidents, breached first, each linking to it', async ({ page, api }) => {
    await dashboard(page, api, 'TECHNICIAN');
    await expect(queue(page).getByRole('heading', { name: 'Service level queue' })).toBeVisible();
    await expect(queue(page)).toContainText('Your open incidents');
    const rows = queue(page).getByRole('list', { name: 'Incidents by deadline' }).getByRole('listitem');
    await expect(rows.first()).toContainText('Breached'); // the demo incidents are days old
    await expect(rows.first()).toContainText(/Due \d+ days ago/);
    await expect(queue(page)).not.toContainText('Cannot open the shared finance drive'); // INC1004 is resolved: nothing left to work on
    const first = rows.first().getByRole('link');
    const title = await first.innerText();
    await first.click();
    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
  });

  test('an admin sees everyone’s, and it is accessible and fits a phone', async ({ page, api }) => {
    await dashboard(page, api, 'ADMIN');
    await expect(queue(page)).toContainText('Open incidents closest to their resolution deadline');
    await expect(queue(page).getByRole('listitem').first()).toBeVisible();
    expect(await axeViolations(page)).toEqual([]);
    await page.setViewportSize({ width: 375, height: 800 });
    await expectNoHorizontalOverflow(page);
  });

  test('reporters get their own dashboard without the queue', async ({ page, api }) => {
    await dashboard(page, api, 'EMPLOYEE');
    await expect(page.getByText('Waiting on you', { exact: true }).first()).toBeVisible();
    await expect(queue(page)).toHaveCount(0);
  });
});

test.describe('SLA queue rules', () => {
  const item = (id: string, status: SlaQueueItem['sla']['status'], due: string | null): SlaQueueItem =>
    ({ incidentId: id, title: id, priority: 'P3', sla: { status, resolutionStatus: status, resolutionDueAt: due, predictedMiss: false } });

  test('breached first, then the nearest deadline, then those without one', () => {
    const sorted = [
      item('INC4', 'RUNNING', null), item('INC3', 'RUNNING', '2026-11-15T12:00:00Z'), item('INC2', 'WARNING', '2026-11-15T10:00:00Z'),
      item('INC1', 'BREACHED', '2026-11-15T11:00:00Z'),
    ].sort(queueOrder).map((i) => i.incidentId);
    expect(sorted).toEqual(['INC1', 'INC2', 'INC3', 'INC4']);
  });

  test('the live mapper keeps the title and counts a prediction only while the clock runs', () => {
    const clock = (status: 'RUNNING' | 'MET') => ({ status, targetMinutes: 240, dueAt: '2026-11-15T12:00:00Z', metAt: null, breachedAt: null });
    const risk = { raisedAt: '', dueAt: '', predictedFinishAt: '', basis: 'CATEGORY' as const, sampleSize: 5, typicalRemainingMinutes: 1, loadFactor: 1 };
    const base = { incidentId: 'INC9', title: 'VPN down', priority: 'P1' as const, response: null, pausedSince: null, evaluatedAt: null };
    expect(toQueueItem({ ...base, status: 'RUNNING', resolution: clock('RUNNING'), risk })).toEqual({
      incidentId: 'INC9', title: 'VPN down', priority: 'P1',
      sla: { status: 'RUNNING', resolutionStatus: 'RUNNING', resolutionDueAt: '2026-11-15T12:00:00Z', predictedMiss: true },
    });
    expect(toQueueItem({ ...base, status: 'MET', resolution: clock('MET'), risk }).sla.predictedMiss).toBe(false);
  });
});
