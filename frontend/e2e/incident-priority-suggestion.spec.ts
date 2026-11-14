/**
 * P4's priority suggestions on the incident page (mock services; the mock has one demo suggestion, on INC1001): Apply goes
 * through the priority dialog prefilled, Dismiss hides it for good, and only admins see it.
 */
import type { Page } from '@playwright/test';
import { test, expect, newUser, type TestRole } from './fixtures';
import { axeViolations, signIn } from './helpers';
import { suggestionHeadline, suggestionReason, type PrioritySuggestionView } from '../src/features/incidents/model/priority';

async function open(page: Page, api: Parameters<typeof signIn>[1], role: TestRole, path: string) {
  await signIn(page, api, newUser(`sugg-${role.toLowerCase()}`, role));
  await page.goto(path);
  await page.getByRole('heading', { level: 1 }).first().waitFor();
}

const card = (page: Page) => page.getByTestId('priority-suggestion');

test.describe('priority suggestions', () => {
  test('an admin sees why, applies it through the dialog, and it goes away', async ({ page, api }) => {
    await open(page, api, 'ADMIN', '/incidents/INC1001');
    await expect(card(page)).toContainText('Raise to P1 Critical');
    await expect(card(page)).toContainText('(now P2 High)');
    await expect(card(page).getByRole('list', { name: 'Why' })).toContainText('3 new reports look like duplicates');
    expect(await axeViolations(page)).toEqual([]);

    await card(page).getByRole('button', { name: 'Apply…' }).click();
    const dialog = page.getByRole('dialog', { name: 'Change priority' });
    await expect(dialog.getByLabel('P1 Critical')).toBeChecked();
    await expect(dialog.getByLabel('Why?')).toHaveValue(/^Applied the suggested priority: 3 new reports/);
    await dialog.getByRole('button', { name: 'Change priority' }).click();

    await expect(page.getByTestId('toasts')).toContainText('Priority raised to P1');
    await expect(page.getByText('P1 Critical').first()).toBeVisible();
    await expect(card(page)).toHaveCount(0);
  });

  test('dismissing hides it, also after a reload', async ({ page, api }) => {
    await open(page, api, 'ADMIN', '/incidents/INC1001');
    await card(page).getByRole('button', { name: 'Dismiss' }).click();
    await expect(page.getByTestId('toasts')).toContainText('will not be suggested again');
    await expect(card(page)).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'Service level' })).toBeVisible();
    await expect(card(page)).toHaveCount(0);
    await expect(page.getByText('P2 High').first()).toBeVisible(); // dismissing changes nothing
  });

  test('technicians do not see it', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN', '/incidents/INC1002');
    await expect(page.getByRole('heading', { name: 'Service level' })).toBeVisible();
    await expect(card(page)).toHaveCount(0);
  });
});

test.describe('priority suggestion wording', () => {
  const s: PrioritySuggestionView = { currentPriority: 'P3', suggestedPriority: 'P1', rules: ['CRITICALITY'], reasons: ['A.', 'B.'], createdAt: '2026-11-14T10:00:00Z' };
  test('says which way and to what, and prefills a reason within the limit', () => {
    expect(suggestionHeadline(s)).toBe('Raise to P1 Critical');
    expect(suggestionHeadline({ ...s, suggestedPriority: 'P4' })).toBe('Lower to P4 Low');
    expect(suggestionReason(s)).toBe('Applied the suggested priority: A. B.');
    expect(suggestionReason({ ...s, reasons: ['x'.repeat(600)] })).toHaveLength(500);
  });
});
