import type { Page } from '@playwright/test';
import { test, expect, newAdmin, newUser, makeAuditEvents, type AuditFake } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, settle, signIn, trackErrors } from './helpers';

type Api = Parameters<typeof signIn>[1];

async function openAdmin(page: Page, api: Api, path: string) {
  await signIn(page, api, newAdmin('gov'));
  await page.goto(path);
  await expect(page.getByRole('navigation', { name: 'Admin sections' })).toBeVisible();
}

const auditRows = (page: Page) => page.locator('main tbody tr');
const summary = (page: Page) => page.getByTestId('pagination-summary');
const dialog = (page: Page) => page.getByRole('dialog');
const search = (page: Page, q: string) => page.getByRole('searchbox').fill(q);
const toast = (page: Page, text: string | RegExp) => expect(page.getByTestId('toasts').getByText(text)).toBeVisible();
const filter = async (page: Page, label: string, option: string) => {
  await page.getByRole('combobox', { name: label }).click();
  await page.getByRole('option', { name: option, exact: true }).click();
};

test.describe('audit logs (backend API)', () => {
  const path = '/admin/audit-logs';
  const lastList = (api: Api & { audit: AuditFake }) => api.audit.requests[api.audit.requests.length - 1];
  const eventRows = auditRows;

  test('the overview’s Recent changes card shows the persisted events from the API, not a local log', async ({ page, api }) => {
    await openAdmin(page, api, '/admin');
    const card = page.getByTestId('recent-changes-card');
    await expect(card.getByText('persisted audit events')).toBeVisible();
    await expect(card.getByText(/demo/i)).toHaveCount(0);
    await expect(page.getByTestId('recent-changes').getByRole('listitem')).toHaveCount(6);
    await expect(page.getByTestId('recent-changes').getByRole('listitem').first()).toContainText('User created');
    const q = api.audit.requests[api.audit.requests.length - 1];
    expect(q.get('size')).toBe('6');
    expect(q.get('sort') ?? 'occurredAt').toBe('occurredAt');
    await card.getByRole('link', { name: /Open the audit logs/ }).click();
    await page.waitForURL('**/admin/audit-logs');
    await expect(eventRows(page)).toHaveCount(10);
  });

  test('lists newest first with readable actors and actions, and pages through the API', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await expect(eventRows(page)).toHaveCount(10);
    await expect(summary(page)).toHaveText('Showing 1–10 of 25 entries');
    await expect(eventRows(page).first()).toContainText('Event number 1');
    await expect(eventRows(page).first()).toContainText('Administrator #10');
    await expect(eventRows(page).first()).toContainText('User created');
    await expect(eventRows(page).nth(4)).toContainText('System: SLA monitor');
    await expect(eventRows(page).nth(1)).toContainText('SLA policy updated');
    await expect(eventRows(page).nth(1)).toContainText('SLA policy');
    for (const h of ['When', 'Actor', 'Action', 'Entity', 'Summary']) await expect(page.getByRole('columnheader', { name: h })).toBeVisible();
    expect(Object.fromEntries(api.audit.requests[0])).toEqual({ sort: 'occurredAt', dir: 'desc', page: '0', size: '10' });

    await page.getByRole('button', { name: /Next/ }).click();
    await expect(summary(page)).toHaveText('Showing 11–20 of 25 entries');
    expect(lastList(api).get('page')).toBe('1');
    await page.getByRole('button', { name: /Next/ }).click();
    await expect(summary(page)).toHaveText('Showing 21–25 of 25 entries');
    await expect(eventRows(page)).toHaveCount(5);
    expect(lastList(api).get('page')).toBe('2');
    await expect(page.getByRole('button', { name: /Next/ })).toBeDisabled();
    await page.getByRole('button', { name: /Previous/ }).click();
    expect(lastList(api).get('page')).toBe('1');
  });

  test('every filter is sent to the API as the documented query parameter, and clearing resets them', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await expect(eventRows(page)).toHaveCount(10);

    await page.getByLabel('Action', { exact: true }).fill('user created');
    await expect(summary(page)).toHaveText('Showing 1–10 of 13 entries');
    await expect.poll(() => lastList(api).get('action')).toBe('USER_CREATED'); // normalised to the stored code
    expect(lastList(api).get('page')).toBe('0');

    await page.getByLabel('Entity type', { exact: true }).fill('user');
    await expect(summary(page)).toHaveText('Showing 1–10 of 13 entries');
    await expect.poll(() => lastList(api).get('entityType')).toBe('USER');

    await page.getByLabel('Entity ID', { exact: true }).fill('100');
    await expect(summary(page)).toHaveText('Showing 1–1 of 1 entries');
    await expect.poll(() => lastList(api).get('entityId')).toBe('100');
    await page.getByLabel('Entity ID', { exact: true }).fill('');

    await filter(page, 'Filter by actor role', 'Administrator');
    await expect.poll(() => lastList(api).get('actorRole')).toBe('ADMIN');
    await page.getByLabel('Actor ID', { exact: true }).fill('10');
    await expect.poll(() => lastList(api).get('actorId')).toBe('10');
    await expect(eventRows(page).first()).toContainText('Administrator #10');

    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await expect(summary(page)).toHaveText('Showing 1–10 of 25 entries');
    expect(Object.fromEntries(lastList(api))).toEqual({ sort: 'occurredAt', dir: 'desc', page: '0', size: '10' });

    await filter(page, 'Filter by actor role', 'System');
    await expect.poll(() => lastList(api).get('actorType')).toBe('SYSTEM');
    expect(lastList(api).has('actorRole')).toBe(false); // system actors have no role
    await expect(summary(page)).toHaveText('Showing 1–5 of 5 entries');
  });

  test('a UTC date range is sent as an inclusive start and an exclusive end at the next midnight', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await page.getByLabel('From', { exact: true }).fill('2026-11-05');
    await page.getByLabel('To', { exact: true }).fill('2026-11-05');
    await expect(summary(page)).toHaveText('Showing 1–10 of 13 entries');
    expect(lastList(api).get('from')).toBe('2026-11-05T00:00:00.000Z');
    expect(lastList(api).get('to')).toBe('2026-11-06T00:00:00.000Z');
    await page.getByLabel('To', { exact: true }).fill('2026-11-04');
    await page.getByLabel('From', { exact: true }).fill('2026-11-04');
    await expect(summary(page)).toHaveText('Showing 1–10 of 12 entries');
    expect(lastList(api).get('to')).toBe('2026-11-05T00:00:00.000Z');
    await page.getByLabel('To', { exact: true }).fill('2026-12-31'); // month boundary handled by Date, not string maths
    expect(lastList(api).get('from')).toBe('2026-11-04T00:00:00.000Z');
    await expect.poll(() => lastList(api).get('to')).toBe('2027-01-01T00:00:00.000Z');
  });

  test('invalid filters are explained in the form and are never sent to the API', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await expect(eventRows(page)).toHaveCount(10);
    const sent = api.audit.requests.length;

    await page.getByLabel('Action', { exact: true }).fill('a');
    await expect(page.getByText('Use 3–60 letters, digits or underscores')).toBeVisible();
    await expect(page.getByTestId('empty-state')).toContainText('Fix the highlighted filters');
    await page.getByLabel('Actor ID', { exact: true }).fill('abc');
    await expect(page.getByText('Enter a positive whole number.')).toBeVisible();
    await page.getByLabel('Entity ID', { exact: true }).fill('bad id');
    await expect(page.getByText('Use up to 40 letters, digits, hyphens or underscores.')).toBeVisible();
    await page.getByLabel('Action', { exact: true }).fill('');
    await page.getByLabel('Actor ID', { exact: true }).fill('');
    await page.getByLabel('Entity ID', { exact: true }).fill('');
    await page.getByLabel('From', { exact: true }).fill('2026-11-05');
    await page.getByLabel('To', { exact: true }).fill('2026-11-01');
    await expect(page.getByRole('alert').filter({ hasText: 'The end date must be on or after the start date.' })).toBeVisible();
    await page.waitForTimeout(600); // longer than the debounce
    expect(api.audit.requests.length, 'no request while any filter is invalid').toBe(sent);

    await page.getByLabel('To', { exact: true }).fill('2026-11-05');
    await expect(eventRows(page).first()).toBeVisible();
    expect(api.audit.requests.length).toBeGreaterThan(sent);
  });

  test('sorting maps the column headers to the backend sort keys and directions', async ({ page, api }) => {
    await openAdmin(page, api, path);
    const head = (n: string) => page.getByRole('columnheader', { name: n, exact: true });
    await expect(head('When')).toHaveAttribute('aria-sort', 'descending');
    await page.getByRole('button', { name: 'When', exact: true }).click();
    await expect(head('When')).toHaveAttribute('aria-sort', 'ascending');
    await expect(eventRows(page).first()).toContainText('Event number 25');
    expect([lastList(api).get('sort'), lastList(api).get('dir')]).toEqual(['occurredAt', 'asc']);
    await page.getByRole('button', { name: 'Action', exact: true }).click();
    await expect(head('Action')).toHaveAttribute('aria-sort', 'ascending');
    await expect.poll(() => [lastList(api).get('sort'), lastList(api).get('dir')]).toEqual(['action', 'asc']);
    await page.getByRole('button', { name: 'Action', exact: true }).click();
    await expect.poll(() => lastList(api).get('dir')).toBe('desc');
    await page.getByRole('button', { name: 'Entity', exact: true }).click();
    await expect.poll(() => lastList(api).get('sort')).toBe('entityType');
    await page.getByRole('button', { name: 'Actor', exact: true }).click();
    await expect.poll(() => lastList(api).get('sort')).toBe('actorRole');
    await expect(lastList(api).get('page')).toBe('0'); // changing the sort returns to the first page
  });

  test('detail loads by id and shows recorded details as plain text; entries without details say so', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await eventRows(page).first().getByRole('button', { name: /View details/ }).click();
    const dlg = dialog(page);
    await expect(dlg.getByRole('heading', { name: 'Audit entry' })).toBeVisible();
    expect(api.audit.detailRequests).toEqual(['/api/admin/audit-events/00000000-0000-4000-8000-000000000001']);
    await expect(dlg).toContainText('Event number 1');
    await expect(dlg).toContainText('Administrator #10');
    await expect(dlg).toContainText('USER_CREATED');
    await expect(dlg).toContainText('100');
    await expect(dlg).toContainText('read-only and cannot be edited or deleted');
    const meta = dlg.getByTestId('audit-metadata');
    await expect(meta.locator('tr', { hasText: 'Changed fields' })).toContainText('name, code');
    await expect(meta.locator('tr', { hasText: 'Count' })).toContainText('3');
    await expect(meta.locator('tr', { hasText: 'Flag' })).toContainText('Yes');
    // Stored markup is shown as text, never interpreted.
    await expect(meta.locator('tr', { hasText: 'Note' })).toContainText('<img src=x onerror=alert(1)>');
    await expect(dlg.locator('img')).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(dlg).toBeHidden();

    await eventRows(page).nth(1).getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByTestId('no-metadata')).toContainText('No additional details were recorded');
    await dialog(page).getByRole('button', { name: 'Close', exact: true }).first().click();
    await expect(dialog(page)).toBeHidden();
  });

  test('state changes and incident ids from the API are displayed', async ({ page, api }) => {
    api.audit.events = [{ ...makeAuditEvents(1)[0], action: 'INCIDENT_ASSIGNED', entityType: 'INCIDENT', entityId: '7', incidentId: 'INC7',
      previousState: 'OPEN', newState: 'IN_PROGRESS', description: 'Assigned to a technician', metadata: {} }];
    await openAdmin(page, api, path);
    await expect(eventRows(page).first()).toContainText('Incident assigned');
    await expect(eventRows(page).first()).toContainText('Incident INC7');
    await eventRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page)).toContainText('Open');
    await expect(dialog(page)).toContainText('In progress');
    await expect(dialog(page)).toContainText('INC7');
  });

  test('a detail that no longer exists says so', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await expect(eventRows(page).first()).toBeVisible();
    api.audit.events = [];
    await eventRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByRole('alert')).toContainText('That audit entry no longer exists.');
  });

  test('is strictly read-only: no edit or delete controls, and only GET requests ever reach the audit API', async ({ page, api }) => {
    const methods: string[] = [];
    page.on('request', (r) => { if (r.url().includes('/api/admin/audit-events')) methods.push(r.method()); });
    await openAdmin(page, api, path);
    await expect(eventRows(page).first()).toBeVisible();
    await expect(page.getByRole('button', { name: /^(edit|delete|remove)/i })).toHaveCount(0);
    await expect(page.getByRole('menuitem')).toHaveCount(0);
    await page.getByRole('button', { name: 'Actor', exact: true }).click();
    await page.getByLabel('Action', { exact: true }).fill('USER_CREATED');
    await eventRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByRole('button', { name: /^(edit|delete|remove|save)/i })).toHaveCount(0);
    await expect(dialog(page).getByRole('textbox')).toHaveCount(0);
    await page.keyboard.press('Escape');
    expect(methods.length).toBeGreaterThan(2);
    expect(new Set(methods)).toEqual(new Set(['GET']));
  });

  test('empty states: no history at all, and no match for the filters', async ({ page, api }) => {
    api.audit.events = [];
    await openAdmin(page, api, path);
    await expect(page.getByTestId('empty-state')).toContainText('No audit entries yet');

    api.audit.events = makeAuditEvents(3);
    await page.reload();
    await expect(eventRows(page)).toHaveCount(3);
    await page.getByLabel('Entity ID', { exact: true }).fill('no-such-id');
    await expect(page.getByTestId('empty-state')).toContainText('No audit entries match your filters');
    await page.getByTestId('empty-state').getByRole('button', { name: 'Clear filters' }).click();
    await expect(eventRows(page)).toHaveCount(3);
  });

  test('loading state is shown while the API answers', async ({ page, api }) => {
    await signIn(page, api, newAdmin('gov-loading'));
    await page.route('**/api/admin/audit-events?*', async (route) => { await new Promise((r) => setTimeout(r, 700)); await route.fallback(); });
    await page.goto(path);
    await expect(page.getByRole('status', { name: 'Loading audit logs' })).toBeVisible();
    await expect(eventRows(page).first()).toBeVisible();
  });

  test('list and detail failures show a safe message and offer retry', async ({ page, api }) => {
    api.audit.fail = { status: 503, code: 'SERVICE_UNAVAILABLE', detail: 'java.sql.SQLException: secret internals', times: 1 };
    await openAdmin(page, api, path);
    const alert = page.getByRole('alert').filter({ hasText: 'The audit log is unavailable right now' });
    await expect(alert).toBeVisible();
    await expect(page.getByText('secret internals')).toHaveCount(0); // server detail never reaches the screen
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(eventRows(page).first()).toBeVisible();

    api.audit.fail = { status: 500, code: 'INTERNAL_ERROR', detail: 'boom', times: 1 };
    await eventRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByRole('alert')).toContainText('The audit log is unavailable right now');
    await dialog(page).getByRole('button', { name: 'Try again' }).click();
    await expect(dialog(page).getByText('Entry identifier')).toBeVisible();
  });

  test('a network failure shows the same safe message', async ({ page, api }) => {
    await signIn(page, api, newAdmin('gov-net'));
    await page.route('**/api/admin/audit-events?*', (route) => route.abort('internetdisconnected'));
    await page.goto(path);
    await expect(page.getByRole('alert').filter({ hasText: 'The audit log is unavailable right now' })).toBeVisible();
  });

  test('a server-side 403 removes everything already shown and offers no retry', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await expect(eventRows(page)).toHaveCount(10);
    api.audit.fail = { status: 403, code: 'FORBIDDEN', detail: 'Operation not permitted', times: 5 };
    await page.getByRole('button', { name: /Next/ }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'You don’t have permission to view audit logs.' })).toBeVisible();
    await expect(eventRows(page)).toHaveCount(0);
    await expect(page.getByText('Event number')).toHaveCount(0);
    await expect(page.getByLabel('Action', { exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Try again' })).toHaveCount(0);
  });

  test('an expired session (401) clears the token and returns to sign-in', async ({ page, api }) => {
    api.audit.fail = { status: 401, code: 'UNAUTHENTICATED', detail: 'Authentication is required', times: 5 };
    await signIn(page, api, newAdmin('gov-401'));
    await page.goto(path);
    await page.waitForURL(/\/\?redirect=%2Fadmin%2Faudit-logs/);
    expect(await page.evaluate(() => localStorage.getItem('it_sm_token'))).toBeNull();
  });

  for (const role of ['EMPLOYEE', 'TECHNICIAN'] as const) {
    test(`${role} never reaches the audit API, including incident audit trails`, async ({ page, api }) => {
      await signIn(page, api, newUser(`gov-${role.toLowerCase()}`, role));
      await page.goto(path);
      await expect(page.getByRole('heading', { name: 'Access denied', level: 1 })).toBeVisible();
      await expect(page.getByText('Event number')).toHaveCount(0);
      expect(api.audit.requests).toHaveLength(0);
      expect(api.audit.detailRequests).toHaveLength(0);
      expect(api.calls.filter((p) => p.includes('/audit'))).toEqual([]);
    });
  }
});

test.describe('governance: quality', () => {
  test('/admin/audit-logs has no axe violations or console errors', async ({ page, api }) => {
    await signIn(page, api, newAdmin('axe'));
    const errors = trackErrors(page);
    await page.goto('/admin/audit-logs');
    await expect(auditRows(page).first()).toBeVisible();
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
    expect(errors).toEqual([]);
  });

  test('the audit detail dialog passes axe and closes with Escape', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/audit-logs');
    await page.getByLabel('Entity ID', { exact: true }).fill('100');
    await expect(summary(page)).toHaveText('Showing 1–1 of 1 entries');
    await auditRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByTestId('audit-metadata')).toBeVisible();
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
    await page.keyboard.press('Escape');
    await expect(dialog(page)).toBeHidden();
  });
});

test.describe('governance: responsive', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  test('/admin/audit-logs fits the phone viewport and the drawer links here', async ({ page, api }) => {
    await signIn(page, api, newAdmin('mobile'));
    await page.goto('/admin');
    await page.getByRole('button', { name: 'Toggle navigation' }).click();
    await dialog(page).getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'Audit logs' }).click();
    await page.waitForURL('**/admin/audit-logs');
    await expect(page.getByRole('heading', { name: 'Audit logs', level: 1 })).toBeVisible();
    await expect(auditRows(page).first()).toBeVisible();
    await page.waitForLoadState('networkidle');
    await expectNoHorizontalOverflow(page);
  });

  test('the audit detail dialog fits the phone viewport', async ({ page, api }) => {
    await signIn(page, api, newAdmin('mobile-dlg'));
    await page.goto('/admin/audit-logs');
    await page.getByLabel('Entity ID', { exact: true }).fill('100');
    await expect(summary(page)).toHaveText('Showing 1–1 of 1 entries');
    await auditRows(page).first().getByRole('button', { name: /View details/ }).click();
    await expect(dialog(page).getByTestId('audit-metadata')).toBeVisible();
    const box = await dialog(page).boundingBox();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(390);
    await expectNoHorizontalOverflow(page);
  });
});
