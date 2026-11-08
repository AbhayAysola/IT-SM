import type { Page } from '@playwright/test';
import { test, expect, newUser } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, settle, signIn, trackErrors } from './helpers';

type Api = Parameters<typeof signIn>[1];

async function openAdmin(page: Page, api: Api, path: string) {
  await signIn(page, api, newUser('gov'));
  await page.goto(path);
  await expect(page.getByRole('navigation', { name: 'Admin sections' })).toBeVisible();
}

const auditRows = (page: Page) => page.locator('main tbody tr');
const summary = (page: Page) => page.getByTestId('pagination-summary');
const dialog = (page: Page) => page.getByRole('dialog');
const search = (page: Page, q: string) => page.getByRole('searchbox').fill(q);
const toast = (page: Page, text: string | RegExp) => expect(page.getByTestId('toasts').getByText(text)).toBeVisible();
const rawAudit = (page: Page) => page.evaluate(() => localStorage.getItem('itsm_admin_v1_audit'));
const auditList = (page: Page) =>
  page.evaluate(() => JSON.parse(localStorage.getItem('itsm_admin_v1_audit') ?? '[]') as { id: string; entity: string; action: string; summary: string; before?: Record<string, string>; after?: Record<string, string> }[]);
const filter = async (page: Page, label: string, option: string) => {
  await page.getByRole('combobox', { name: label }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
};
const strategy = (page: Page, name: RegExp) => page.getByRole('radio', { name });
const fillField = (page: Page, label: string, value: string) => page.getByLabel(label, { exact: true }).fill(value);
const saveBtn = (page: Page) => page.getByRole('button', { name: /^Save (changes|configuration)$/ });

const ASSIGN = '/admin/assignment-settings';

test.describe('assignment settings', () => {
  test('shows the current configuration summary and is reachable from the sidebar', async ({ page, api }) => {
    await openAdmin(page, api, '/admin');
    await page.getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'Assignment settings' }).click();
    await expect(page.getByRole('heading', { name: 'Assignment settings', level: 1 })).toBeVisible();
    const s = page.getByTestId('assignment-summary');
    await expect(s).toContainText('Skill-based');
    await expect(s).toContainText('Minimum skill match');
    await expect(s).toContainText('60%');
    await expect(s).toContainText('Fall back to round-robin');
    await expect(s).toContainText('After 30 min');
    await expect(s).toContainText('Top 3 shown to assigners');
    await expect(s).toContainText('Last changed by Amara Okafor');
    await expect(strategy(page, /^Skill-based/)).toBeChecked();
    await expect(page.getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'Assignment settings' })).toHaveAttribute('aria-current', 'page');
  });

  test('Save is disabled until something changes, and Discard restores the saved values', async ({ page, api }) => {
    await openAdmin(page, api, ASSIGN);
    await expect(saveBtn(page)).toBeDisabled();
    await fillField(page, 'Escalate unassigned incidents after (minutes)', '45');
    await expect(saveBtn(page)).toBeEnabled();
    await expect(page.getByText('You have unsaved changes.')).toBeVisible();
    await page.getByRole('button', { name: 'Discard changes' }).click();
    await expect(page.getByLabel('Escalate unassigned incidents after (minutes)')).toHaveValue('30');
    await expect(saveBtn(page)).toBeDisabled();
  });

  test('strategy-specific panels follow the selected strategy', async ({ page, api }) => {
    await openAdmin(page, api, ASSIGN);
    await expect(page.getByTestId('settings-SKILL_BASED')).toBeVisible();
    await strategy(page, /^Round-robin/).check();
    await expect(page.getByTestId('settings-ROUND_ROBIN')).toBeVisible();
    await expect(page.getByTestId('settings-SKILL_BASED')).toHaveCount(0);
    await strategy(page, /^Manual/).check();
    await expect(page.getByTestId('settings-MANUAL')).toBeVisible();
    await expect(page.getByLabel('If no technician qualifies')).toHaveCount(0); // no fallback for manual
  });

  test('switching strategy requires a fallback, asks for confirmation, then persists across reload', async ({ page, api }) => {
    await openAdmin(page, api, ASSIGN);
    await strategy(page, /^Round-robin/).check();
    await saveBtn(page).click();
    await expect(page.getByText('The fallback must be different from the selected strategy.')).toBeVisible();
    await expect(dialog(page)).toHaveCount(0);

    await page.getByLabel('If no technician qualifies').click();
    await page.getByRole('option', { name: 'Fall back to manual' }).click();
    await expect(page.getByText('The fallback must be different')).toHaveCount(0);
    await saveBtn(page).click();
    await expect(dialog(page)).toContainText('Switch to Round-robin?');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByTestId('assignment-summary')).toContainText('Skill-based'); // unchanged

    await saveBtn(page).click();
    await dialog(page).getByRole('button', { name: 'Switch strategy' }).click();
    await toast(page, 'Assignment settings saved: Round-robin.');
    const s = page.getByTestId('assignment-summary');
    await expect(s).toContainText('Round-robin');
    await expect(s).toContainText('Fall back to manual');
    await expect(s).toContainText('All technicians');

    await page.reload();
    await expect(page.getByTestId('assignment-summary')).toContainText('Round-robin');
    await expect(strategy(page, /^Round-robin/)).toBeChecked();
  });

  test('skill-based and common fields are validated before saving, and nothing is saved or audited on failure', async ({ page, api }) => {
    await openAdmin(page, api, ASSIGN);
    await page.goto('/admin/audit-logs'); // seeds the audit store so we can compare
    await expect(auditRows(page).first()).toBeVisible();
    await page.goto(ASSIGN);
    const before = await rawAudit(page);

    await fillField(page, 'Minimum skill match (%)', '0');
    await fillField(page, 'Escalate unassigned incidents after (minutes)', '2');
    await fillField(page, 'Technician recommendations shown', '6');
    await saveBtn(page).click();
    await expect(page.getByText('Enter a whole percentage from 1 to 100.')).toBeVisible();
    await expect(page.getByText('Enter a whole number of minutes from 5 to 1440.')).toBeVisible();
    await expect(page.getByText('Enter a whole number from 0 to 5.')).toBeVisible();

    for (const [label, bad, msg] of [
      ['Minimum skill match (%)', '101', 'Enter a whole percentage from 1 to 100.'],
      ['Minimum skill match (%)', 'abc', 'Enter a whole percentage from 1 to 100.'],
      ['Escalate unassigned incidents after (minutes)', '1441', 'Enter a whole number of minutes from 5 to 1440.'],
      ['Escalate unassigned incidents after (minutes)', '', 'Enter a whole number of minutes from 5 to 1440.'],
      ['Technician recommendations shown', '-1', 'Enter a whole number from 0 to 5.'],
      ['Technician recommendations shown', '2.5', 'Enter a whole number from 0 to 5.'],
    ] as const) {
      await fillField(page, label, bad);
      await saveBtn(page).click();
      await expect(page.getByText(msg).first()).toBeVisible();
    }
    expect(await rawAudit(page)).toBe(before);
    await page.reload();
    await expect(page.getByTestId('assignment-summary')).toContainText('60%'); // nothing persisted
  });

  test('a valid settings change saves without a confirmation and appends an audit entry with before/after values', async ({ page, api }) => {
    await openAdmin(page, api, ASSIGN);
    await fillField(page, 'Escalate unassigned incidents after (minutes)', '45');
    await fillField(page, 'Minimum skill match (%)', '75');
    await fillField(page, 'Technician recommendations shown', '0');
    await saveBtn(page).click();
    await expect(dialog(page)).toHaveCount(0);
    await toast(page, 'Assignment settings saved: Skill-based.');
    const s = page.getByTestId('assignment-summary');
    await expect(s).toContainText('After 45 min');
    await expect(s).toContainText('75%');
    await expect(s).toContainText('Recommendations');
    await expect(s).toContainText('Off');

    const entries = await auditList(page);
    expect(entries[0]).toMatchObject({ entity: 'ASSIGNMENT_SETTINGS', action: 'UPDATE', summary: 'Updated skill-based assignment settings' });
    expect(entries[0].before).toMatchObject({ unassignedEscalationMinutes: '30', recommendationCount: '3', 'skillBased.minSkillMatchPct': '60' });
    expect(entries[0].after).toMatchObject({ unassignedEscalationMinutes: '45', recommendationCount: '0', 'skillBased.minSkillMatchPct': '75' });

    await page.getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'Audit logs' }).click();
    await expect(page.getByRole('heading', { name: 'Audit logs', level: 1 })).toBeVisible();
    const top = auditRows(page).first();
    await expect(top).toContainText('Updated skill-based assignment settings');
    await expect(top).not.toContainText('Demo data');
    await top.getByRole('button', { name: /View details/ }).click();
    const changes = dialog(page).getByTestId('audit-changes');
    await expect(changes).toContainText('Unassigned Escalation Minutes');
    await expect(changes.locator('tr', { hasText: 'Unassigned Escalation Minutes' })).toContainText('30');
    await expect(changes.locator('tr', { hasText: 'Unassigned Escalation Minutes' })).toContainText('45');
  });

  test('round-robin team pool validation uses real technician data', async ({ page, api }) => {
    await openAdmin(page, api, ASSIGN);
    await strategy(page, /^Round-robin/).check();
    await page.getByLabel('If no technician qualifies').click();
    await page.getByRole('option', { name: 'Fall back to manual' }).click();
    await page.getByRole('radio', { name: 'Selected teams only' }).check();
    await expect(page.getByText('(no technicians)')).toBeVisible(); // Security has none
    await saveBtn(page).click();
    await expect(page.getByText('Select at least one team for the pool.')).toBeVisible();
    await page.getByLabel('Security').check();
    await saveBtn(page).click();
    await expect(page.getByText('No technician profiles in Security.')).toBeVisible();
    await page.getByLabel('Security').uncheck();
    await page.getByLabel('Service Desk').check();
    await page.getByLabel('Skip technicians who are at their maximum workload').uncheck();
    await saveBtn(page).click();
    await dialog(page).getByRole('button', { name: 'Switch strategy' }).click();
    await toast(page, 'Assignment settings saved: Round-robin.');
    const s = page.getByTestId('assignment-summary');
    await expect(s).toContainText('Teams: Service Desk');
    await expect(s).toContainText('Still eligible');
  });

  test('manual strategy has no fallback and stores the team-lead notification choice', async ({ page, api }) => {
    await openAdmin(page, api, ASSIGN);
    await strategy(page, /^Manual/).check();
    await page.getByLabel('Notify team leads when an incident is waiting for assignment').uncheck();
    await saveBtn(page).click();
    await dialog(page).getByRole('button', { name: 'Switch strategy' }).click();
    await toast(page, 'Assignment settings saved: Manual.');
    const s = page.getByTestId('assignment-summary');
    await expect(s).toContainText('Not notified');
    await expect(s).not.toContainText('If nobody qualifies');
    const e = (await auditList(page))[0];
    expect(e.summary).toBe('Changed assignment strategy from Skill-based to Manual');
  });

  test('a failed save shows the error, writes no audit entry, and succeeds on retry', async ({ page, api }) => {
    await openAdmin(page, api, ASSIGN);
    await page.goto('/admin/audit-logs');
    await expect(auditRows(page).first()).toBeVisible();
    await page.goto(ASSIGN);
    await expect(page.getByTestId('assignment-summary')).toBeVisible();
    const before = await rawAudit(page);
    await fillField(page, 'Escalate unassigned incidents after (minutes)', '60');
    await page.evaluate(() => localStorage.setItem('itsm_admin_simulate_error', '1'));
    await saveBtn(page).click();
    await expect(page.getByRole('alert').filter({ hasText: 'directory service is unavailable' })).toBeVisible();
    expect(await rawAudit(page)).toBe(before);
    await page.evaluate(() => localStorage.removeItem('itsm_admin_simulate_error'));
    await saveBtn(page).click();
    await toast(page, 'Assignment settings saved: Skill-based.');
    expect((await auditList(page)).length).toBe(JSON.parse(before!).length + 1);
  });

  test('empty state when nothing is configured; first save configures and audits', async ({ page, api }) => {
    await signIn(page, api, newUser('gov-empty'));
    await page.evaluate(() => localStorage.setItem('itsm_admin_v1_assignment', '[]'));
    await page.goto(ASSIGN);
    await expect(page.getByTestId('empty-state')).toContainText('No assignment strategy configured');
    await expect(page.getByText('Choose an assignment strategy', { exact: true })).toBeVisible();
    await page.getByLabel('If no technician qualifies').click();
    await page.getByRole('option', { name: 'Fall back to manual' }).click();
    await saveBtn(page).click();
    await expect(dialog(page)).toHaveCount(0); // no confirmation for a first configuration
    await toast(page, 'Assignment settings saved: Skill-based.');
    await expect(page.getByTestId('assignment-summary')).toContainText('Fall back to manual');
    expect((await auditList(page))[0].summary).toBe('Configured assignment strategy: Skill-based');
  });

  test('error state offers retry', async ({ page, api }) => {
    await signIn(page, api, newUser('gov-err'));
    await page.evaluate(() => localStorage.setItem('itsm_admin_simulate_error', '1'));
    await page.goto(ASSIGN);
    await expect(page.getByRole('alert').filter({ hasText: 'directory service is unavailable' }).first()).toBeVisible();
    await page.evaluate(() => localStorage.removeItem('itsm_admin_simulate_error'));
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByTestId('assignment-summary')).toBeVisible();
  });
});

test.describe('audit logs', () => {
  const path = '/admin/audit-logs';

  test('lists newest first with demo-data labels and paginates', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await expect(auditRows(page)).toHaveCount(10);
    await expect(summary(page)).toHaveText('Showing 1–10 of 22 entries');
    await expect(auditRows(page).first()).toContainText('Invited Ben Adeyemi');
    await expect(auditRows(page).first()).toContainText('Demo data');
    for (const h of ['When', 'Actor', 'Action', 'Entity', 'Summary']) await expect(page.getByRole('columnheader', { name: h })).toBeVisible();
    await page.getByRole('button', { name: /Next/ }).click();
    await expect(summary(page)).toHaveText('Showing 11–20 of 22 entries');
    await page.getByRole('button', { name: /Next/ }).click();
    await expect(summary(page)).toHaveText('Showing 21–22 of 22 entries');
    await expect(auditRows(page)).toHaveCount(2);
    await expect(page.getByRole('button', { name: /Next/ })).toBeDisabled();
  });

  test('searches by actor, action, entity type and entity identifier', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await search(page, 'daniel');
    await expect(summary(page)).toHaveText('Showing 1–10 of 10 entries');
    await search(page, 'u-005');
    await expect(summary(page)).toHaveText('Showing 1–2 of 2 entries');
    await search(page, 'status change');
    await expect(summary(page)).toHaveText('Showing 1–4 of 4 entries');
    await search(page, 'sla policy');
    await expect(summary(page)).toHaveText('Showing 1–4 of 4 entries');
    await search(page, 'no-such-thing');
    await expect(page.getByTestId('empty-state')).toContainText('No audit entries match your filters');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await expect(summary(page)).toHaveText('Showing 1–10 of 22 entries');
  });

  test('filters by action, entity type and a date range, and reports an invalid range', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await filter(page, 'Filter by action', 'Delete');
    await expect(summary(page)).toHaveText('Showing 1–2 of 2 entries');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await filter(page, 'Filter by entity type', 'Technician');
    await expect(summary(page)).toHaveText('Showing 1–4 of 4 entries');
    await filter(page, 'Filter by action', 'Update');
    await expect(summary(page)).toHaveText('Showing 1–2 of 2 entries');
    await filter(page, 'Filter by action', 'Create');
    await search(page, 'zzz');
    await expect(page.getByTestId('empty-state')).toBeVisible();
    await page.getByRole('button', { name: 'Clear filters' }).first().click();

    await page.getByLabel('From', { exact: true }).fill('2026-11-01');
    await page.getByLabel('To', { exact: true }).fill('2026-11-03');
    await expect(summary(page)).toHaveText('Showing 1–3 of 3 entries');
    await page.getByLabel('From', { exact: true }).fill('2026-11-04');
    await page.getByLabel('To', { exact: true }).fill('2026-11-04');
    await expect(summary(page)).toHaveText('Showing 1–1 of 1 entries');
    await expect(auditRows(page).first()).toContainText('Invited Ben Adeyemi');
    await page.getByLabel('From', { exact: true }).fill('2026-11-05');
    await page.getByLabel('To', { exact: true }).fill('2026-11-01');
    await expect(page.getByRole('alert').filter({ hasText: 'The end date must be on or after the start date.' })).toBeVisible();
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await expect(summary(page)).toHaveText('Showing 1–10 of 22 entries');
  });

  test('sorts by when, actor, action and entity', async ({ page, api }) => {
    await openAdmin(page, api, path);
    const head = (n: string) => page.getByRole('columnheader', { name: n, exact: true });
    await expect(head('When')).toHaveAttribute('aria-sort', 'descending');
    await page.getByRole('button', { name: 'When', exact: true }).click();
    await expect(head('When')).toHaveAttribute('aria-sort', 'ascending');
    await expect(auditRows(page).first()).toContainText('Created IT service “Core Network”');
    await page.getByRole('button', { name: 'Actor', exact: true }).click();
    await expect(auditRows(page).first()).toContainText('Amara Okafor');
    await page.getByRole('button', { name: 'Actor', exact: true }).click();
    await expect(head('Actor')).toHaveAttribute('aria-sort', 'descending');
    await expect(auditRows(page).first()).toContainText('Daniel Whitfield');
    await page.getByRole('button', { name: 'Action', exact: true }).click();
    await expect(auditRows(page).first()).toContainText('Create');
    await page.getByRole('button', { name: 'Entity', exact: true }).click();
    await expect(auditRows(page).first()).toContainText('Category');
  });

  test('detail view shows metadata and before/after values; entries without changes say so', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await search(page, 'Mei-Ling Chen from EMPLOYEE');
    await expect(summary(page)).toHaveText('Showing 1–1 of 1 entries');
    await auditRows(page).first().getByRole('button', { name: /View details/ }).click();
    const dlg = dialog(page);
    await expect(dlg.getByRole('heading', { name: 'Audit entry' })).toBeVisible();
    await expect(dlg).toContainText('Role change');
    await expect(dlg).toContainText('u-005');
    await expect(dlg).toContainText('Amara Okafor');
    await expect(dlg).toContainText('Demo data');
    await expect(dlg).toContainText('read-only and cannot be edited or deleted');
    const row = dlg.getByTestId('audit-changes').locator('tr', { hasText: 'Role' }).last();
    await expect(row).toContainText('EMPLOYEE');
    await expect(row).toContainText('TECHNICIAN');
    await page.keyboard.press('Escape');
    await expect(dlg).toBeHidden();

    await search(page, 'Created IT service “Core Network”');
    await expect(auditRows(page).first()).toContainText('Created IT service “Core Network”');
    await auditRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByTestId('no-changes')).toContainText('No before/after values were recorded');
    await dialog(page).getByRole('button', { name: 'Close', exact: true }).first().click();
    await expect(dialog(page)).toBeHidden();
  });

  test('a real admin action appears first, unlabelled as demo, with recorded before/after values', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await page.getByRole('button', { name: 'Actions for Elena Petrova' }).click();
    await page.getByRole('menuitem', { name: 'Suspend' }).click();
    await dialog(page).getByRole('button', { name: 'Suspend user' }).click();
    await toast(page, 'Elena Petrova was suspended.');
    await page.getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'Audit logs' }).click();
    await expect(summary(page)).toHaveText('Showing 1–10 of 23 entries');
    const top = auditRows(page).first();
    await expect(top).toContainText('Suspended Elena Petrova');
    await expect(top).not.toContainText('Demo data');
    await top.getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page)).toContainText('Recorded from an administrator action');
    const r = dialog(page).getByTestId('audit-changes').locator('tr', { hasText: 'Status' });
    await expect(r).toContainText('ACTIVE');
    await expect(r).toContainText('SUSPENDED');
  });

  test('is strictly read-only: no edit or delete controls, and viewing never changes stored entries', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await expect(auditRows(page).first()).toBeVisible();
    const before = await rawAudit(page);
    await expect(page.getByRole('button', { name: /^(edit|delete|remove|clear)/i })).toHaveCount(0);
    await expect(page.getByRole('menuitem')).toHaveCount(0);
    await filter(page, 'Filter by action', 'Update');
    await page.getByRole('button', { name: 'Actor', exact: true }).click();
    await auditRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByRole('button', { name: /^(edit|delete|remove|save)/i })).toHaveCount(0);
    await expect(dialog(page).getByRole('textbox')).toHaveCount(0);
    await page.keyboard.press('Escape');
    expect(await rawAudit(page)).toBe(before);
  });

  test('empty state when there is no history', async ({ page, api }) => {
    await signIn(page, api, newUser('gov-empty-audit'));
    await page.evaluate(() => localStorage.setItem('itsm_admin_v1_audit', '[]'));
    await page.goto(path);
    await expect(page.getByTestId('empty-state')).toContainText('No audit entries yet');
  });

  test('list and detail errors offer retry', async ({ page, api }) => {
    await signIn(page, api, newUser('gov-err-audit'));
    await page.evaluate(() => localStorage.setItem('itsm_admin_simulate_error', '1'));
    await page.goto(path);
    await expect(page.getByRole('alert').filter({ hasText: 'directory service is unavailable' })).toBeVisible();
    await page.evaluate(() => localStorage.removeItem('itsm_admin_simulate_error'));
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(auditRows(page).first()).toBeVisible();

    await page.evaluate(() => localStorage.setItem('itsm_admin_simulate_error', '1'));
    await auditRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByRole('alert')).toContainText('directory service is unavailable');
    await page.evaluate(() => localStorage.removeItem('itsm_admin_simulate_error'));
    await dialog(page).getByRole('button', { name: 'Try again' }).click();
    await expect(dialog(page).getByText('Entry identifier')).toBeVisible();
  });
});

test.describe('governance: quality', () => {
  for (const path of [ASSIGN, '/admin/audit-logs']) {
    test(`${path} has no axe violations or console errors`, async ({ page, api }) => {
      await signIn(page, api, newUser('axe'));
      const errors = trackErrors(page);
      await page.goto(path);
      if (path === ASSIGN) await expect(page.getByTestId('assignment-summary')).toBeVisible();
      else await expect(auditRows(page).first()).toBeVisible();
      await settle(page);
      expect(await axeViolations(page)).toEqual([]);
      expect(errors).toEqual([]);
    });
  }

  test('each strategy panel passes axe', async ({ page, api }) => {
    await openAdmin(page, api, ASSIGN);
    for (const n of [/^Round-robin/, /^Manual/]) {
      await strategy(page, n).check();
      if (n.source.includes('Round')) await page.getByRole('radio', { name: 'Selected teams only' }).check();
      expect(await axeViolations(page)).toEqual([]);
    }
  });

  test('the switch-strategy confirmation dialog passes axe', async ({ page, api }) => {
    await openAdmin(page, api, ASSIGN);
    await strategy(page, /^Manual/).check();
    await saveBtn(page).click();
    await expect(dialog(page)).toBeVisible();
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
    await page.keyboard.press('Escape');
    await expect(dialog(page)).toBeHidden();
  });

  test('the audit detail dialog passes axe and closes with Escape', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/audit-logs');
    await search(page, 'Mei-Ling Chen from EMPLOYEE');
    await expect(summary(page)).toHaveText('Showing 1–1 of 1 entries');
    await auditRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByTestId('audit-changes')).toBeVisible();
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
    await page.keyboard.press('Escape');
    await expect(dialog(page)).toBeHidden();
  });
});

test.describe('governance: responsive', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  for (const [path, heading, link] of [[ASSIGN, 'Assignment settings', 'Assignment settings'], ['/admin/audit-logs', 'Audit logs', 'Audit logs']] as const) {
    test(`${path} fits the phone viewport and the drawer links here`, async ({ page, api }) => {
      await signIn(page, api, newUser('mobile'));
      await page.goto('/admin');
      await page.getByRole('button', { name: 'Toggle navigation' }).click();
      await dialog(page).getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: link }).click();
      await page.waitForURL(`**${path}`);
      await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible();
      if (path === ASSIGN) await expect(page.getByTestId('assignment-summary')).toBeVisible();
      else await expect(auditRows(page).first()).toBeVisible();
      await page.waitForLoadState('networkidle');
      await expectNoHorizontalOverflow(page);
    });
  }

  test('audit detail dialog and the strategy confirmation fit the phone viewport', async ({ page, api }) => {
    await signIn(page, api, newUser('mobile-dlg'));
    await page.goto('/admin/audit-logs');
    await search(page, 'Mei-Ling Chen from EMPLOYEE');
    await expect(summary(page)).toHaveText('Showing 1–1 of 1 entries');
    await auditRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByTestId('audit-changes')).toBeVisible();
    let box = await dialog(page).boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(390);
    await expectNoHorizontalOverflow(page);
    await page.keyboard.press('Escape');

    await page.goto(ASSIGN);
    await strategy(page, /^Manual/).check();
    await saveBtn(page).click();
    box = await dialog(page).boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(390);
  });
});
