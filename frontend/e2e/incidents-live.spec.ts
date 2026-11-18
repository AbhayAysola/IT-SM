/**
 * The P3 incident workspace against the REAL backend (no mock, no fake API). On demand only:
 *
 *   E2E_INCIDENTS_LIVE=1 E2E_LIVE_API=1 npm run test:e2e:run   (see e2e/README.md, "Live incidents")
 *
 * It needs a frontend built with NEXT_PUBLIC_INCIDENTS_API=live, a backend on E2E_API_URL, and a disposable PostgreSQL 16
 * in a Docker container (E2E_PG_CONTAINER, default e2e-pg). The backend must run with registration enabled
 * (APP_REGISTRATION_ENABLED=true). Accounts are registered through the API; the test then uses SQL for what has no public
 * endpoint a test could call: roles, technician profiles, one category and service, and the incident rows (intake is
 * covered by P2's own specs, so these tests start at CLASSIFIED or ASSIGNED).
 */
import { execFileSync } from 'node:child_process';
import type { Browser, Page } from '@playwright/test';
import { test, expect, newUser, API_URL, type TestUser } from './fixtures';

test.skip(process.env.E2E_INCIDENTS_LIVE !== '1', 'live incidents run is opt-in (E2E_INCIDENTS_LIVE=1)');

const PG = process.env.E2E_PG_CONTAINER ?? 'e2e-pg';
const sql = (query: string): string =>
  execFileSync('docker', ['exec', '-i', PG, 'psql', '-U', 'postgres', '-d', 'itsm', '-At', '-c', query], { encoding: 'utf8' }).trim();

const status = (page: Page) => page.locator('main .flex.flex-wrap.items-center.gap-2.text-sm');
const toast = (page: Page, text: string | RegExp) => page.getByTestId('toasts').getByText(text);
const timeline = (page: Page) => page.getByRole('list', { name: 'Investigation timeline' });

async function register(request: Parameters<Parameters<typeof test>[2]>[0]['request'], user: TestUser, role: string): Promise<number> {
  const res = await request.post(`${API_URL}/api/auth/register`, { data: { email: user.email, password: user.password, name: user.name } });
  expect(res.ok(), `register failed: ${res.status()}`).toBeTruthy();
  sql(`UPDATE users SET role = '${role}' WHERE email = '${user.email}'`);
  return Number(sql(`SELECT id FROM users WHERE email = '${user.email}'`));
}

/** A separate browser context per person, signed in through the real form. */
async function personPage(browser: Browser, baseURL: string, user: TestUser): Promise<Page> {
  const page = await (await browser.newContext({ baseURL, reducedMotion: 'reduce' })).newPage();
  await page.goto('/');
  await page.getByLabel('Email address').fill(user.email);
  await page.getByLabel('Password').fill(user.password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL('**/dashboard');
  return page;
}

let catalog: { category: number; service: number } | undefined;
/** One category and one service for this run (the incidents table has foreign keys to both). */
function seedCatalog() {
  if (catalog) return catalog;
  const tag = `${Date.now().toString(36)}${Math.floor(Math.random() * 1000)}`.toUpperCase();
  const category = Number(sql(`INSERT INTO incident_categories (code, name) VALUES ('LIVE_${tag}', 'Live test category ${tag}') RETURNING id`).split('\n')[0]);
  const service = Number(sql(`INSERT INTO it_services (name, business_criticality) VALUES ('Live test service ${tag}', 3) RETURNING id`).split('\n')[0]);
  return (catalog = { category, service });
}

/** Gives a TECHNICIAN account a profile (what P1's admin page does) and a skill in the test category. */
function makeTechnician(userId: number, proficiency: number) {
  sql(`INSERT INTO technician_profiles (user_id, max_workload, experience_years, available) VALUES (${userId}, 5, 3, true)`);
  sql(`INSERT INTO technician_skills (user_id, category_id, proficiency) VALUES (${userId}, ${seedCatalog().category}, ${proficiency})`);
}

const incidentId = () => `INC7${Date.now().toString().slice(-6)}${Math.floor(Math.random() * 90 + 10)}`;
function insertIncident(id: string, title: string, reporter: number, state: 'CLASSIFIED' | 'ASSIGNED', assignee?: number) {
  sql(
    `INSERT INTO incidents (id, title, description, state, reporter_id, assignee_id, category_id, service_id, priority, assigned_at) VALUES ` +
      `('${id}', '${title}', 'Reported through the live test', '${state}', ${reporter}, ${assignee ?? 'NULL'}, ${seedCatalog().category}, ${seedCatalog().service}, 'P2', ${assignee ? 'now()' : 'NULL'})`,
  );
}

test.describe('incidents against the real backend', () => {
  test('an incident is worked from assignment to closed by three different people', async ({ browser, request }, testInfo) => {
    const baseURL = String(testInfo.project.use.baseURL);
    const [reporterU, techU, adminU, strangerU] = ['rep', 'tech', 'admin', 'stranger'].map((l) => newUser(`live-${l}`));
    const reporterId = await register(request, reporterU, 'EMPLOYEE');
    const techId = await register(request, techU, 'TECHNICIAN');
    await register(request, adminU, 'ADMIN');
    await register(request, strangerU, 'EMPLOYEE');
    const id = incidentId();
    insertIncident(id, 'VPN drops every hour', reporterId, 'ASSIGNED', techId);

    // ---- the technician: sees it in their list, works it, asks a question
    const tech = await personPage(browser, baseURL, techU);
    await tech.goto('/incidents');
    await expect(tech.getByRole('tab', { name: /^Active \(1\)/ })).toBeVisible();
    const row = tech.getByRole('row').filter({ hasText: id });
    await expect(row).toContainText('VPN drops every hour');
    await expect(row).toContainText('Needs you');
    await row.getByRole('link').click();
    await expect(tech.getByRole('heading', { name: 'VPN drops every hour', level: 1 })).toBeVisible();
    await tech.getByRole('button', { name: 'Accept incident' }).click();
    await expect(toast(tech, 'Incident accepted.')).toBeVisible();
    await tech.getByRole('button', { name: 'Start investigating' }).click();
    await expect(status(tech)).toContainText('In progress');
    await tech.getByLabel('Add a note').fill('Gateway logs show the tunnel renegotiating.');
    await tech.getByRole('button', { name: 'Add note' }).click();
    await expect(toast(tech, 'Note added.')).toBeVisible();
    await tech.getByRole('button', { name: 'Ask the reporter a question' }).click();
    await tech.getByLabel('Your question').fill('Which floor are you on?');
    await tech.getByRole('dialog').getByRole('button', { name: 'Ask the reporter a question' }).click();
    await expect(status(tech)).toContainText('Waiting for reporter');

    // ---- the reporter: sees the question but not the internal note, and answers
    const reporter = await personPage(browser, baseURL, reporterU);
    await reporter.goto('/incidents');
    await expect(reporter.getByRole('tab', { name: /^Open \(1\)/ })).toBeVisible();
    const rrow = reporter.getByRole('row').filter({ hasText: id });
    await expect(rrow).toContainText('Needs you');
    await rrow.getByRole('link').click();
    await expect(timeline(reporter)).toContainText('Which floor are you on?');
    await expect(timeline(reporter)).not.toContainText('renegotiating');
    await reporter.getByRole('button', { name: 'Send reply' }).click();
    await reporter.getByLabel('Your reply').fill('The third floor.');
    await reporter.getByRole('dialog').getByRole('button', { name: 'Send reply' }).click();
    await expect(toast(reporter, 'Your reply was sent.')).toBeVisible();
    await expect(status(reporter)).toContainText('Being worked on');

    // ---- the technician resolves; the reporter confirms and rates
    await tech.reload();
    await expect(timeline(tech)).toContainText('The third floor.');
    await expect(timeline(tech)).toContainText('renegotiating'); // staff see internal notes
    await tech.getByRole('button', { name: 'Mark as resolved' }).click();
    await tech.getByLabel('How was it fixed?').fill('Raised the tunnel lifetime on the gateway.');
    await tech.getByRole('dialog').getByRole('button', { name: 'Mark as resolved' }).click();
    await expect(status(tech)).toContainText('Resolved, awaiting confirmation');

    await reporter.reload();
    await reporter.getByRole('button', { name: 'Yes, it’s fixed' }).click();
    await reporter.getByRole('dialog').getByRole('button', { name: 'Yes, it’s fixed' }).click();
    await expect(status(reporter)).toContainText('Fix confirmed');
    await reporter.getByLabel('5 of 5: Excellent').check();
    await reporter.getByRole('button', { name: 'Send feedback' }).click();
    await expect(reporter.getByText('Rated 5 of 5')).toBeVisible();

    // ---- the admin closes it; it persisted in the database, not the browser
    const admin = await personPage(browser, baseURL, adminU);
    await admin.goto(`/incidents/${id}`);
    await admin.getByRole('button', { name: 'Close incident' }).click();
    await admin.getByRole('dialog').getByRole('button', { name: 'Close incident' }).click();
    await expect(status(admin)).toContainText('Closed');
    expect(sql(`SELECT state || ',' || (closed_at IS NOT NULL) || ',' || (resolved_at IS NOT NULL) FROM incidents WHERE id = '${id}'`)).toBe('CLOSED,true,true');
    expect(sql(`SELECT count(*) FROM incident_feedback WHERE incident_id = '${id}'`)).toBe('1');

    // ---- someone else's incident is "not found", and the list holds only their own
    const stranger = await personPage(browser, baseURL, strangerU);
    await stranger.goto(`/incidents/${id}`);
    await expect(stranger.getByRole('heading', { name: 'Incident not found' })).toBeVisible();
    await stranger.goto('/incidents');
    await expect(stranger.getByText('You have no open incidents.')).toBeVisible();
  });

  test('an admin assigns from the ranked suggestions, then reassigns with a reason', async ({ browser, request }, testInfo) => {
    const baseURL = String(testInfo.project.use.baseURL);
    const [reporterU, adminU] = ['rep2', 'admin2'].map((l) => newUser(`live-${l}`));
    const expert = { ...newUser('live-expert'), name: 'Elsa Expert' };
    const novice = { ...newUser('live-novice'), name: 'Nico Novice' };
    const reporterId = await register(request, reporterU, 'EMPLOYEE');
    await register(request, adminU, 'ADMIN');
    const expertId = await register(request, expert, 'TECHNICIAN');
    const noviceId = await register(request, novice, 'TECHNICIAN');
    makeTechnician(expertId, 5);
    makeTechnician(noviceId, 1);
    const id = incidentId();
    insertIncident(id, 'Printer on floor 2 is offline', reporterId, 'CLASSIFIED');

    const admin = await personPage(browser, baseURL, adminU);
    await admin.goto('/incidents');
    await expect(admin.getByRole('row').filter({ hasText: id })).toContainText('Awaiting assignment');
    await admin.getByRole('button', { name: `Assign ${id}` }).click();
    const dialog = admin.getByRole('dialog');
    await expect(dialog.getByText('Best match')).toBeVisible();
    // Real names from the directory, best skill first.
    await expect(dialog.getByRole('radio').first()).toBeChecked();
    await expect(dialog.locator('label').first()).toContainText('Elsa Expert');
    await expect(dialog.locator('label').nth(1)).toContainText('Nico Novice');
    await dialog.getByRole('button', { name: 'Assign', exact: true }).click();
    await expect(toast(admin, /assigned/i)).toBeVisible();
    expect(sql(`SELECT state || ',' || assignee_id FROM incidents WHERE id = '${id}'`)).toBe(`ASSIGNED,${expertId}`);

    // Reassign: a reason is required, and the incident moves to the other technician.
    await admin.goto(`/incidents/${id}`);
    await expect(admin.getByRole('main')).toContainText('Elsa Expert');
    await admin.getByRole('button', { name: /^Reassign/ }).click();
    const move = admin.getByRole('dialog');
    await move.locator('label').filter({ hasText: 'Nico Novice' }).click();
    await move.getByRole('button', { name: 'Reassign', exact: true }).click();
    await expect(move.getByText('Please say why the incident is moving.')).toBeVisible();
    await move.getByLabel('Why is it moving?').fill('Expert is needed on the VPN outage.');
    await move.getByRole('button', { name: 'Reassign', exact: true }).click();
    await expect(toast(admin, /assigned|reassigned/i)).toBeVisible();
    expect(sql(`SELECT state || ',' || assignee_id FROM incidents WHERE id = '${id}'`)).toBe(`ASSIGNED,${noviceId}`);

    // The new assignee sees it; the old one no longer does.
    const nico = await personPage(browser, baseURL, novice);
    await nico.goto('/incidents');
    await expect(nico.getByRole('row').filter({ hasText: id })).toBeVisible();
    const elsa = await personPage(browser, baseURL, expert);
    await elsa.goto('/incidents');
    await expect(elsa.getByRole('row').filter({ hasText: id })).toHaveCount(0);
  });

  test('a long list is paged by the backend, and search is literal', async ({ browser, request }, testInfo) => {
    const baseURL = String(testInfo.project.use.baseURL);
    const reporterU = newUser('live-pager');
    const reporterId = await register(request, reporterU, 'EMPLOYEE');
    sql(
      `INSERT INTO incidents (title, description, state, reporter_id, category_id, service_id, priority) ` +
        `SELECT 'Bulk ' || n, 'bulk', 'CLASSIFIED', ${reporterId}, ${seedCatalog().category}, ${seedCatalog().service}, 'P3' FROM generate_series(1, 25) AS n`,
    );
    sql(`INSERT INTO incidents (title, description, state, reporter_id, category_id, service_id, priority) VALUES ('Disk 100% full', 'x', 'CLASSIFIED', ${reporterId}, ${seedCatalog().category}, ${seedCatalog().service}, 'P1')`);

    const page = await personPage(browser, baseURL, reporterU);
    await page.goto('/incidents');
    const summary = page.getByTestId('pagination-summary');
    await expect(summary).toHaveText('Showing 1–20 of 26 incidents');
    await expect(page.getByRole('row').nth(1)).toContainText('Disk 100% full'); // most urgent first, from the server
    await page.getByRole('button', { name: /Next/ }).click();
    await expect(summary).toHaveText('Showing 21–26 of 26 incidents');
    await page.getByLabel('Search incidents').fill('100%');
    await expect(page.getByRole('row').filter({ hasText: 'Disk 100% full' })).toBeVisible();
    await expect(page.getByRole('row')).toHaveCount(2); // header + the one match: "%" is literal, not a wildcard
    await expect(summary).toHaveCount(0);
  });
});
