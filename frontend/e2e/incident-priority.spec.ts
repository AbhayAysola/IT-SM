/**
 * Changing an incident's priority (admins; mock services): the dialog, its checks, and the result on the page. The rules
 * themselves are pure and tested at the end.
 */
import type { Page } from '@playwright/test';
import { test, expect, newUser, type TestRole } from './fixtures';
import { axeViolations, signIn } from './helpers';
import { canChangePriority, direction, priorityChangeErrors } from '../src/features/incidents/model/priority';

async function open(page: Page, api: Parameters<typeof signIn>[1], role: TestRole, path: string) {
  await signIn(page, api, newUser(`prio-${role.toLowerCase()}`, role));
  await page.goto(path);
  await page.getByRole('heading', { level: 1 }).first().waitFor();
}

const dialog = (page: Page) => page.getByRole('dialog', { name: 'Change priority' });

test.describe('incident priority', () => {
  test('an admin raises the priority with a reason, and the page follows', async ({ page, api }) => {
    await open(page, api, 'ADMIN', '/incidents/INC1001'); // P2, assigned
    await expect(page.getByText('P2 High').first()).toBeVisible();
    await page.getByRole('button', { name: 'Change priority' }).click();
    await expect(dialog(page)).toContainText('The SLA deadlines follow the new priority from this moment');
    expect(await axeViolations(page)).toEqual([]);

    await dialog(page).getByRole('button', { name: 'Change priority' }).click();
    await expect(dialog(page)).toContainText('Choose the new priority.');
    await expect(dialog(page)).toContainText('Say why the priority changes.');

    await dialog(page).getByLabel(/P2 High/).check();
    await dialog(page).getByLabel('Why?').fill('Half the library is offline now');
    await dialog(page).getByRole('button', { name: 'Change priority' }).click();
    await expect(dialog(page)).toContainText('It is already P2 High');

    await dialog(page).getByLabel('P1 Critical').check();
    await dialog(page).getByRole('button', { name: 'Change priority' }).click();
    await expect(dialog(page)).toHaveCount(0);
    await expect(page.getByTestId('toasts')).toContainText('Priority raised to P1');
    await expect(page.getByText('P1 Critical').first()).toBeVisible();
    await expect(page.getByTestId('sla-panel')).toContainText('30 min target'); // the mock's P1 resolution target
    await expect(page.getByRole('button', { name: 'Change priority' })).toBeFocused();
  });

  test('is not offered once the incident is resolved', async ({ page, api }) => {
    await open(page, api, 'ADMIN', '/incidents/INC1004'); // resolved, awaiting confirmation
    await expect(page.getByRole('heading', { name: 'Service level' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Change priority' })).toHaveCount(0);
  });

  test('is not offered to technicians, even on their own incident', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN', '/incidents/INC1002');
    await expect(page.getByRole('heading', { name: 'Service level' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Change priority' })).toHaveCount(0);
  });
});

test.describe('priority change rules', () => {
  test('a new priority and a reason are needed', () => {
    expect(priorityChangeErrors('P3', 'P1', 'Finance is down')).toBeNull();
    expect(priorityChangeErrors('P3', null, '')).toEqual({ priority: 'Choose the new priority.', reason: 'Say why the priority changes.' });
    expect(priorityChangeErrors('P3', 'P3', 'x')?.priority).toContain('already P3 Medium');
    expect(priorityChangeErrors('P3', 'P1', 'x'.repeat(501))?.reason).toContain('under 500');
  });

  test('admins change it while the incident is worked on', () => {
    expect(canChangePriority('ADMIN', 'IN_PROGRESS')).toBe(true);
    expect(canChangePriority('ADMIN', 'PENDING_INFO')).toBe(true);
    for (const s of ['NEW', 'RESOLVED', 'VERIFIED', 'CLOSED'] as const) expect(canChangePriority('ADMIN', s)).toBe(false);
    expect(canChangePriority('TECHNICIAN', 'IN_PROGRESS')).toBe(false);
  });

  test('P1 is the highest', () => {
    expect(direction('P3', 'P1')).toBe('raised');
    expect(direction('P1', 'P4')).toBe('lowered');
  });
});
