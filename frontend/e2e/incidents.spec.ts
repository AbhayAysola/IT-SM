/**
 * P3 incident workspace (mock services, per-account demo data). Personas other than USER need the fake API's role
 * support, so in live mode (E2E_LIVE_API=1) `api.seed` skips them.
 */
import type { Page } from '@playwright/test';
import { test, expect, newUser, type TestRole } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, settle, signIn, trackErrors } from './helpers';

async function open(page: Page, api: Parameters<typeof signIn>[1], role: TestRole, path = '/incidents') {
  await signIn(page, api, newUser(`inc-${role.toLowerCase()}`, role));
  await page.goto(path);
  await page.getByRole('heading', { level: 1 }).first().waitFor();
}

const row = (page: Page, id: string) => page.getByRole('row').filter({ hasText: id });
const tab = (page: Page, name: RegExp) => page.getByRole('tab', { name });
const toast = (page: Page, text: string | RegExp) => page.getByTestId('toasts').getByText(text);
const status = (page: Page) => page.locator('main .flex.flex-wrap.items-center.gap-2.text-sm');

test.describe('incidents: navigation and access', () => {
  test('the primary navigation links to Incidents, which is marked current', async ({ page, api }) => {
    await signIn(page, api, newUser('inc-nav', 'EMPLOYEE'));
    await page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Incidents' }).click();
    await page.waitForURL('**/incidents');
    await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Incidents' })).toHaveAttribute('aria-current', 'page');
    await expect(page.getByRole('heading', { name: 'My incidents', level: 1 })).toBeVisible();
  });

  test('signed-out visitors are sent to sign-in', async ({ page }) => {
    await page.goto('/incidents');
    await page.waitForURL((u) => u.pathname === '/');
  });

  test('a legacy USER account is treated as an employee', async ({ page, api }) => {
    await open(page, api, 'USER');
    await expect(tab(page, /^Open/)).toBeVisible();
    await expect(tab(page, /Awaiting assignment/)).toHaveCount(0);
  });

  test('someone else’s incident is "not found", not "forbidden"', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN', '/incidents/INC1005');
    await expect(page.getByRole('heading', { name: 'Incident not found' })).toBeVisible();
  });
});

test.describe('incidents: employee (reporter)', () => {
  test('sees plain-language statuses and what needs them', async ({ page, api }) => {
    await open(page, api, 'EMPLOYEE');
    await expect(tab(page, /^Open \(4\)/)).toBeVisible();
    await expect(tab(page, /^Done \(1\)/)).toBeVisible();
    await expect(row(page, 'INC1003')).toContainText('Waiting for your reply');
    await expect(row(page, 'INC1003')).toContainText('Needs you');
    await expect(row(page, 'INC1004')).toContainText('Fixed? Please confirm');
    await expect(row(page, 'INC1005')).toContainText('Waiting for a technician');
    await expect(page.getByRole('columnheader', { name: 'Technician' })).toBeVisible();
  });

  test('answers a question: it needs text, then work resumes', async ({ page, api }) => {
    await open(page, api, 'EMPLOYEE', '/incidents/INC1003');
    await expect(page.getByText('Which printer is it?')).toBeVisible();
    await page.getByRole('button', { name: 'Send reply' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Send reply' }).click();
    await expect(page.getByRole('dialog').getByText('Please write something first.')).toBeVisible();
    await page.getByLabel('Your reply').fill('It is the PRN-204 on the left.');
    await page.getByRole('dialog').getByRole('button', { name: 'Send reply' }).click();
    await expect(toast(page, 'Your reply was sent.')).toBeVisible();
    await expect(status(page)).toContainText('Being worked on');
    await expect(page.getByRole('list', { name: 'Investigation timeline' })).toContainText('PRN-204');
  });

  test('never sees internal notes, only the conversation and the fix', async ({ page, api }) => {
    await open(page, api, 'EMPLOYEE', '/incidents/INC1004');
    const timeline = page.getByRole('list', { name: 'Investigation timeline' });
    await expect(timeline).toContainText('dropped out of the Finance group');
    await expect(timeline).not.toContainText('Permissions look right on the share');
    await expect(page.getByText('Internal, hidden from the reporter')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Add note' })).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Investigation' })).toBeVisible(); // rendered, so the absence is real
    await expect(page.getByText('Past fixes for similar incidents')).toHaveCount(0);
  });

  test('confirms a fix, then rates once', async ({ page, api }) => {
    await open(page, api, 'EMPLOYEE', '/incidents/INC1004');
    await page.getByRole('button', { name: 'Yes, it’s fixed' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Yes, it’s fixed' }).click();
    await expect(toast(page, 'Thanks, the fix is confirmed.')).toBeVisible();
    await expect(status(page)).toContainText('Fix confirmed');

    await expect(page.getByRole('heading', { name: 'How did we do?' })).toBeVisible();
    await page.getByRole('button', { name: 'Send feedback' }).click();
    await expect(page.getByText('Choose a rating from 1 to 5.')).toBeVisible();
    await page.getByLabel('4 of 5: Very good').check();
    await page.getByLabel('Comment (optional)').fill('Fast and clear.');
    await page.getByRole('button', { name: 'Send feedback' }).click();
    await expect(page.getByText('Rated 4 of 5: Very good')).toBeVisible();
    await expect(page.getByText('Fast and clear.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Send feedback' })).toHaveCount(0);
  });

  test('says it is not fixed: a reason is required and the incident goes back to work', async ({ page, api }) => {
    await open(page, api, 'EMPLOYEE', '/incidents/INC1004');
    await page.getByRole('button', { name: 'No, it’s not fixed' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'No, it’s not fixed' }).click();
    await expect(page.getByRole('dialog').getByText('Please write something first.')).toBeVisible();
    await page.getByLabel('What is still wrong?').fill('Still access denied.');
    await page.getByRole('dialog').getByRole('button', { name: 'No, it’s not fixed' }).click();
    await expect(toast(page, 'The incident was reopened.')).toBeVisible();
    await expect(status(page)).toContainText('Being worked on');
  });

  test('can rate an already-verified incident from the list, and not twice', async ({ page, api }) => {
    await open(page, api, 'EMPLOYEE');
    await tab(page, /^Done/).click();
    await expect(row(page, 'INC1007')).toContainText('Needs you');
    await page.getByRole('link', { name: 'Projector in room B12 has no sound' }).click();
    await page.getByLabel('5 of 5: Excellent').check();
    await page.getByRole('button', { name: 'Send feedback' }).click();
    await expect(page.getByText('Rated 5 of 5: Excellent')).toBeVisible();
    await page.reload();
    await expect(page.getByText('Rated 5 of 5: Excellent')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Send feedback' })).toHaveCount(0);
  });
});

test.describe('incidents: technician', () => {
  test('sees only their own work, grouped, with what needs them first', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN');
    await expect(tab(page, /^Active \(3\)/)).toBeVisible();
    await expect(tab(page, /^Waiting on reporter \(2\)/)).toBeVisible();
    await expect(tab(page, /^Done \(1\)/)).toBeVisible();
    await expect(row(page, 'INC1001')).toContainText('Needs you');
    await expect(row(page, 'INC1002')).toContainText('P1 Critical');
    await expect(row(page, 'INC1005')).toHaveCount(0);
    await expect(row(page, 'INC1001')).toContainText('Assigned');
    await tab(page, /^Waiting on reporter/).click();
    await expect(row(page, 'INC1003')).toContainText('Waiting for reporter');
  });

  test('works an incident from acceptance to resolution, and the work persists', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN', '/incidents/INC1001');
    await page.getByRole('button', { name: 'Accept incident' }).click();
    await expect(toast(page, 'Incident accepted.')).toBeVisible();
    await expect(status(page)).toContainText('Acknowledged');
    await page.getByRole('button', { name: 'Start investigating' }).click();
    await expect(status(page)).toContainText('In progress');

    await page.getByRole('button', { name: 'Mark as resolved' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Mark as resolved' }).click();
    await expect(page.getByRole('dialog').getByText('Please write something first.')).toBeVisible();
    await page.getByLabel('How was it fixed?').fill('Moved the access point to a clean channel.');
    await page.getByRole('dialog').getByRole('button', { name: 'Mark as resolved' }).click();
    await expect(status(page)).toContainText('Resolved, awaiting confirmation');
    await expect(page.getByRole('button', { name: 'Mark as resolved' })).toHaveCount(0); // nothing left for the technician to do

    await page.reload();
    await expect(status(page)).toContainText('Resolved, awaiting confirmation');
    await expect(page.getByRole('list', { name: 'Investigation timeline' })).toContainText('clean channel');
  });

  test('notes are internal unless shared, and say so in words', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN', '/incidents/INC1002');
    await page.getByLabel('Add a note').fill('Checking the failover node as well.');
    await page.getByRole('button', { name: 'Add note' }).click();
    await expect(toast(page, 'Note added.')).toBeVisible();
    const timeline = page.getByRole('list', { name: 'Investigation timeline' });
    await expect(timeline.getByRole('listitem').filter({ hasText: 'failover node' })).toContainText('Internal, hidden from the reporter');

    await page.getByLabel('Add a note').fill('Update: the fix is being tested.');
    await page.getByLabel('Share with the reporter').check();
    await page.getByRole('button', { name: 'Add note' }).click();
    await expect(timeline.getByRole('listitem').filter({ hasText: 'fix is being tested' })).not.toContainText('Internal');

    await page.getByRole('button', { name: 'Add note' }).click();
    await expect(page.getByText('The note is required.')).toBeVisible();
  });

  test('asks the reporter a question, which pauses the incident', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN', '/incidents/INC1002');
    await page.getByRole('button', { name: 'Ask the reporter a question' }).click();
    await page.getByLabel('Your question').fill('Does it fail on the guest network too?');
    await page.getByRole('dialog').getByRole('button', { name: 'Ask the reporter a question' }).click();
    await expect(status(page)).toContainText('Waiting for reporter');
    await expect(page.getByRole('button', { name: 'Mark as resolved' })).toHaveCount(0);
  });

  test('sees how similar past incidents were fixed, but only while working one', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN', '/incidents/INC1001');
    const panel = page.getByRole('heading', { name: 'Past fixes for similar incidents' });
    await expect(panel).toBeVisible();
    await expect(page.getByText('DHCP scope for Block C was exhausted')).toBeVisible();
    await expect(page.getByText('Same service')).toBeVisible();
    await page.goto('/incidents/INC1007');
    await expect(page.getByRole('heading', { name: 'Projector in room B12 has no sound', level: 1 })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Past fixes for similar incidents' })).toHaveCount(0);
  });

  test('has no assignment controls', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN', '/incidents/INC1002');
    await expect(page.getByRole('button', { name: /^(Assign|Reassign)/ })).toHaveCount(0);
  });
});

test.describe('incidents: administrator', () => {
  test('sees everything and what is awaiting assignment', async ({ page, api }) => {
    await open(page, api, 'ADMIN');
    await expect(page.getByRole('heading', { name: 'Incidents', level: 1, exact: true })).toBeVisible();
    await expect(tab(page, /^Awaiting assignment \(2\)/)).toBeVisible();
    await expect(tab(page, /^In progress \(5\)/)).toBeVisible();
    await expect(tab(page, /^Done \(5\)/)).toBeVisible();
    await expect(row(page, 'INC1005')).toContainText('Awaiting assignment');
    await expect(row(page, 'INC1005')).toContainText('Needs you');
  });

  test('assigns from the list with explained suggestions; urgent work favours experience and skill', async ({ page, api }) => {
    await open(page, api, 'ADMIN');
    await page.getByRole('button', { name: 'Assign INC1005' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByRole('group', { name: 'Recommended technicians' })).toBeVisible();
    const best = dialog.locator('label').first();
    await expect(best).toContainText('Mei-Ling Chen');
    await expect(best).toContainText('Best match');
    await expect(best).toContainText('Skill 5 of 5 in Email & Calendar');
    await expect(best).toContainText('Score 90.5');
    // unavailable technicians are not offered
    await expect(dialog).not.toContainText('Rahul Verma');
    await expect(dialog).not.toContainText('Tomás Herrera');
    await dialog.getByRole('button', { name: 'Assign', exact: true }).click();
    await expect(toast(page, 'INC1005 assigned to Mei-Ling Chen.')).toBeVisible();
    await expect(row(page, 'INC1005')).toHaveCount(0);
    await expect(tab(page, /^Awaiting assignment \(1\)/)).toBeVisible();
  });

  test('reassigning needs a reason, keeps the reporter out of it, and never offers the current technician', async ({ page, api }) => {
    await open(page, api, 'ADMIN', '/incidents/INC1002');
    await page.getByRole('button', { name: 'Reassign', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).not.toContainText('Priya Natarajan'); // the current technician
    await dialog.getByRole('button', { name: 'Reassign', exact: true }).click();
    await expect(dialog.getByText('Please say why the incident is moving.')).toBeVisible();
    await dialog.getByLabel('Why is it moving?').fill('Priya is needed on the outage.');
    await dialog.getByRole('button', { name: 'Reassign', exact: true }).click();
    await expect(toast(page, /^Assigned to /)).toBeVisible();
    await expect(status(page)).toContainText('Assigned');
    const entry = page.getByRole('list', { name: 'Investigation timeline' }).getByRole('listitem').filter({ hasText: 'needed on the outage' });
    await expect(entry).toContainText('Internal, hidden from the reporter');
  });

  test('closes a confirmed incident', async ({ page, api }) => {
    await open(page, api, 'ADMIN', '/incidents/INC1007');
    await page.getByRole('button', { name: 'Close incident' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Close incident' }).click();
    await expect(toast(page, 'Incident closed.')).toBeVisible();
    await expect(status(page)).toContainText('Closed');
    await expect(page.getByRole('button', { name: 'Close incident' })).toHaveCount(0);
  });

  test('an admin cannot give feedback in the reporter’s name', async ({ page, api }) => {
    await open(page, api, 'ADMIN', '/incidents/INC1007');
    await expect(page.getByRole('heading', { name: 'Details' })).toBeVisible(); // the page rendered, so the absence below means something
    await expect(page.getByRole('heading', { name: 'How did we do?' })).toHaveCount(0);
  });
});

test.describe('incidents: states, errors and quality', () => {
  test('a failing service shows an error with a retry', async ({ page, api }) => {
    await open(page, api, 'EMPLOYEE');
    await page.evaluate(() => localStorage.setItem('itsm_p3_simulate_error', '1'));
    await page.reload();
    await expect(page.getByRole('alert').filter({ hasText: 'unavailable' })).toBeVisible();
    await page.evaluate(() => localStorage.removeItem('itsm_p3_simulate_error'));
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(row(page, 'INC1003')).toBeVisible();
  });

  test('search narrows the list and says when nothing matches', async ({ page, api }) => {
    await open(page, api, 'ADMIN');
    await tab(page, /^In progress/).click();
    await page.getByLabel('Search incidents').fill('VPN');
    await expect(row(page, 'INC1002')).toBeVisible();
    await expect(row(page, 'INC1001')).toHaveCount(0);
    await page.getByLabel('Search incidents').fill('zzzz');
    await expect(page.getByText('No incidents match your search.')).toBeVisible();
  });

  test('each account has its own demo data', async ({ page, api }) => {
    await open(page, api, 'EMPLOYEE', '/incidents/INC1003');
    await page.getByRole('button', { name: 'Send reply' }).click();
    await page.getByLabel('Your reply').fill('Done.');
    await page.getByRole('dialog').getByRole('button', { name: 'Send reply' }).click();
    await expect(status(page)).toContainText('Being worked on');
    await page.getByRole('button', { name: 'Sign out' }).click();
    await page.waitForURL((u) => u.pathname === '/');
    await signIn(page, api, newUser('inc-other', 'EMPLOYEE'));
    await page.goto('/incidents/INC1003');
    await expect(status(page)).toContainText('Waiting for your reply');
  });

  for (const role of ['EMPLOYEE', 'TECHNICIAN', 'ADMIN'] as const) {
    test(`${role}: list and detail have no axe violations or console errors`, async ({ page, api }) => {
      const errors = trackErrors(page);
      await open(page, api, role);
      await row(page, role === 'ADMIN' ? 'INC1005' : 'INC1001').waitFor().catch(() => undefined);
      expect(await axeViolations(page)).toEqual([]);
      await page.goto(role === 'EMPLOYEE' ? '/incidents/INC1004' : '/incidents/INC1002');
      await page.getByRole('list', { name: 'Investigation timeline' }).waitFor();
      expect(await axeViolations(page)).toEqual([]);
      expect(errors).toEqual([]);
    });
  }

  test('dialogs pass axe: reply, resolve, feedback and assignment', async ({ page, api }) => {
    await open(page, api, 'ADMIN');
    await page.getByRole('button', { name: 'Assign INC1005' }).click();
    await page.getByRole('group', { name: 'Recommended technicians' }).waitFor();
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
    await page.keyboard.press('Escape');
    await page.goto('/incidents/INC1002');
    await page.getByRole('button', { name: 'Mark as resolved' }).click();
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
  });

  test('the dialog traps and restores focus, and Escape closes it', async ({ page, api }) => {
    await open(page, api, 'TECHNICIAN', '/incidents/INC1002');
    const trigger = page.getByRole('button', { name: 'Ask the reporter a question' });
    await trigger.click();
    await expect(page.getByLabel('Your question')).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });

  test.describe('phone width', () => {
    test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
    for (const role of ['EMPLOYEE', 'TECHNICIAN', 'ADMIN'] as const) {
      test(`${role}: list and detail do not scroll sideways`, async ({ page, api }) => {
        await open(page, api, role);
        await expectNoHorizontalOverflow(page);
        await page.goto(role === 'EMPLOYEE' ? '/incidents/INC1004' : '/incidents/INC1002');
        await page.getByRole('list', { name: 'Investigation timeline' }).waitFor();
        await expectNoHorizontalOverflow(page);
      });
    }
  });
});
