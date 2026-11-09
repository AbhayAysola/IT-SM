import type { Page } from '@playwright/test';
import { test, expect, newAdmin } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, settle, signIn, trackErrors } from './helpers';

/** Signs in through the real form, then opens an admin route. Mock admin data lives in this context's localStorage. */
async function openAdmin(page: Page, api: Parameters<typeof signIn>[1], path = '/admin') {
  await signIn(page, api, newAdmin('admin'));
  await page.goto(path);
  await expect(page.getByRole('navigation', { name: 'Admin sections' })).toBeVisible();
}

const rows = (page: Page) => page.locator('tbody tr');
const search = (page: Page, q: string) => page.getByRole('searchbox').fill(q);
const rowMenu = (page: Page, name: string) => page.getByRole('button', { name: `Actions for ${name}` }).click();
const toast = (page: Page, text: string | RegExp) => expect(page.getByTestId('toasts').getByText(text)).toBeVisible();

test.describe('admin: access and shell', () => {
  test('unauthenticated /admin redirects to sign-in with no API calls', async ({ page, api }) => {
    await page.goto('/admin/users');
    await page.waitForURL(/\/\?redirect=%2Fadmin%2Fusers/);
    expect(api.calls).toEqual([]);
  });

  test('sidebar navigates between admin pages and marks the current one', async ({ page, api }) => {
    await openAdmin(page, api);
    const nav = page.getByRole('navigation', { name: 'Admin sections' });
    await expect(nav.getByRole('link', { name: 'Admin overview' })).toHaveAttribute('aria-current', 'page');
    await nav.getByRole('link', { name: 'Users' }).click();
    await expect(page.getByRole('heading', { name: 'Users', level: 1 })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Users' })).toHaveAttribute('aria-current', 'page');
    await nav.getByRole('link', { name: 'Technicians' }).click();
    await expect(page.getByRole('heading', { name: 'Technicians', level: 1 })).toBeVisible();
    await page.getByRole('link', { name: 'My dashboard' }).first().click();
    await page.waitForURL('**/dashboard');
  });

  test('existing dashboard links to administration, and sign out works from the admin shell', async ({ page, api }) => {
    await signIn(page, api, newAdmin('admin-link'));
    await page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Administration' }).click();
    await page.waitForURL('**/admin');
    await page.getByRole('button', { name: /Account menu/ }).click();
    await page.getByRole('menuitem', { name: 'Sign out' }).click();
    await page.waitForURL(/\/(\?.*)?$/);
    await page.goto('/admin');
    await page.waitForURL(/\/\?redirect=%2Fadmin/);
  });
});

test.describe('admin: overview', () => {
  test('shows figures, attention items that deep-link into filtered lists, and recent changes', async ({ page, api }) => {
    await openAdmin(page, api);
    await expect(page.getByRole('region', { name: 'Key figures' })).toContainText('Total users');
    await expect(page.getByTestId('recent-changes').getByRole('listitem')).toHaveCount(6);

    await page.getByRole('link', { name: /pending invitations/ }).click();
    await page.waitForURL('**/admin/users?status=INVITED');
    await expect(rows(page)).toHaveCount(2);
    await expect(page.getByRole('combobox', { name: 'Filter by status' })).toContainText('Invited');
  });

  test('error state offers retry and recovers', async ({ page, api }) => {
    await signIn(page, api, newAdmin('overview-err'));
    await page.evaluate(() => localStorage.setItem('itsm_admin_simulate_error', '1'));
    await page.goto('/admin');
    await expect(page.getByRole('alert').filter({ hasText: 'We couldn’t load this.' }).first()).toBeVisible();
    await page.evaluate(() => localStorage.removeItem('itsm_admin_simulate_error'));
    await page.getByRole('button', { name: 'Try again' }).first().click();
    await expect(page.getByRole('region', { name: 'Key figures' })).toBeVisible();
  });

  test('a change made elsewhere shows up in recent changes', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await rowMenu(page, 'Elena Petrova');
    await page.getByRole('menuitem', { name: 'Suspend' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Suspend user' }).click();
    await toast(page, 'Elena Petrova was suspended.');
    await page.getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'Admin overview' }).click();
    await expect(page.getByTestId('recent-changes')).toContainText('Suspended Elena Petrova');
  });
});

test.describe('admin: users', () => {
  test('lists with a loading state, paginates, and searches', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await expect(rows(page)).toHaveCount(8);
    await expect(page.getByTestId('pagination-summary')).toHaveText('Showing 1–8 of 25 users');
    await page.getByRole('button', { name: /Next/ }).click();
    await expect(page.getByTestId('pagination-summary')).toHaveText('Showing 9–16 of 25 users');
    await search(page, 'priya');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Priya Natarajan');
    await expect(page.getByTestId('pagination-summary')).toHaveText('Showing 1–1 of 1 users');
  });

  test('filters by role, status and department, shows an empty state, and clears', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await page.getByRole('combobox', { name: 'Filter by role' }).click();
    await page.getByRole('option', { name: 'Administrator' }).click();
    await expect(rows(page)).toHaveCount(2);
    await page.getByRole('combobox', { name: 'Filter by status' }).click();
    await page.getByRole('option', { name: 'Suspended' }).click();
    await expect(page.getByTestId('empty-state')).toContainText('No users match your filters');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await expect(rows(page)).toHaveCount(8);
    await page.getByRole('combobox', { name: 'Filter by department' }).click();
    await page.getByRole('option', { name: 'Library' }).click();
    await expect(rows(page)).toHaveCount(2);
  });

  test('sorts by name in both directions', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await expect(rows(page).first()).toContainText('Aisha Rahman');
    await page.getByRole('button', { name: 'User', exact: true }).click();
    await expect(page.getByRole('columnheader', { name: 'User', exact: true })).toHaveAttribute('aria-sort', 'descending');
    await expect(rows(page).first()).toContainText('Yuki Tanaka');
  });

  test('add user validates, rejects duplicates, then saves and persists across reload', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await page.getByRole('button', { name: 'Add user' }).first().click();
    const dlg = page.getByRole('dialog');
    await dlg.getByRole('button', { name: 'Add user' }).click();
    await expect(dlg.getByText('Enter the full name')).toBeVisible();
    await expect(dlg.getByText('Enter a valid email address')).toBeVisible();
    await expect(dlg.getByText('Choose a department.')).toBeVisible();
    await expect(dlg).toBeVisible();

    await dlg.getByLabel('Full name').fill('Test Person');
    await dlg.getByLabel('Email address').fill('priya.natarajan@campus.example');
    await dlg.getByLabel('Department').click();
    await page.getByRole('option', { name: 'Finance' }).click();
    await dlg.getByRole('button', { name: 'Add user' }).click();
    await expect(dlg.getByRole('alert')).toContainText('already registered');

    await dlg.getByLabel('Email address').fill('test.person@campus.example');
    await dlg.getByRole('button', { name: 'Add user' }).click();
    await toast(page, 'Test Person was invited.');
    await expect(dlg).toBeHidden();

    await page.reload();
    await search(page, 'test.person');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Invited');
  });

  test('edit changes a role and cancelling keeps the original', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await search(page, 'elena');
    await rowMenu(page, 'Elena Petrova');
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    const dlg = page.getByRole('dialog');
    await dlg.getByLabel('Full name').fill('');
    await dlg.getByRole('button', { name: 'Save changes' }).click();
    await expect(dlg.getByText('Enter the full name')).toBeVisible();
    await dlg.getByLabel('Full name').fill('Elena P. Petrova');
    await dlg.getByRole('button', { name: 'Cancel' }).click();
    await expect(rows(page).first()).toContainText('Elena Petrova');

    await rowMenu(page, 'Elena Petrova');
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    await dlg.getByLabel('Full name').fill('Elena P. Petrova');
    await dlg.getByLabel('Role').click();
    await page.getByRole('option', { name: 'Administrator' }).click();
    await dlg.getByRole('button', { name: 'Save changes' }).click();
    await toast(page, 'Elena P. Petrova was updated.');
    await expect(rows(page).first()).toContainText('Administrator');
  });

  test('suspend asks for confirmation; reactivate works', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await search(page, 'hannah');
    await rowMenu(page, 'Hannah Becker');
    await page.getByRole('menuitem', { name: 'Suspend' }).click();
    const dlg = page.getByRole('dialog');
    await expect(dlg).toContainText('Suspend Hannah Becker?');
    await dlg.getByRole('button', { name: 'Cancel' }).click();
    await expect(rows(page).first()).toContainText('Active');

    await rowMenu(page, 'Hannah Becker');
    await page.getByRole('menuitem', { name: 'Suspend' }).click();
    await dlg.getByRole('button', { name: 'Suspend user' }).click();
    await expect(rows(page).first()).toContainText('Suspended');
    await rowMenu(page, 'Hannah Becker');
    await page.getByRole('menuitem', { name: 'Reactivate' }).click();
    await toast(page, 'Hannah Becker was reactivated.');
    await expect(rows(page).first()).toContainText('Active');
  });

  test('delete asks for confirmation and removes the user', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await search(page, 'victor');
    await rowMenu(page, 'Victor Ng');
    await page.getByRole('menuitem', { name: 'Delete' }).click();
    await expect(page.getByRole('dialog')).toContainText('cannot be undone');
    await page.getByRole('dialog').getByRole('button', { name: 'Delete user' }).click();
    await toast(page, 'Victor Ng was deleted.');
    await expect(page.getByTestId('empty-state')).toBeVisible();
  });

  test('the last active administrator cannot be removed', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await page.getByRole('combobox', { name: 'Filter by role' }).click();
    await page.getByRole('option', { name: 'Administrator' }).click();
    await rowMenu(page, 'Daniel Whitfield');
    await page.getByRole('menuitem', { name: 'Delete' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Delete user' }).click();
    await expect(rows(page)).toHaveCount(1);

    await rowMenu(page, 'Amara Okafor');
    await page.getByRole('menuitem', { name: 'Delete' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Delete user' }).click();
    await expect(page.getByRole('dialog').getByRole('alert')).toContainText('only active administrator');
    await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
    await expect(rows(page)).toHaveCount(1);
  });

  test('bulk suspend works on a selection', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await page.getByLabel('Select Elena Petrova').check();
    await page.getByLabel('Select Aisha Rahman').check();
    await expect(page.getByText('2 selected')).toBeVisible();
    await page.getByRole('button', { name: 'Suspend selected' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Suspend selected' }).click();
    await toast(page, '2 users suspended.');
    await expect(page.getByText('2 selected')).toBeHidden();
    await search(page, 'aisha');
    await expect(rows(page).first()).toContainText('Suspended');
  });

  test('error state offers retry', async ({ page, api }) => {
    await signIn(page, api, newAdmin('users-err'));
    await page.evaluate(() => localStorage.setItem('itsm_admin_simulate_error', '1'));
    await page.goto('/admin/users');
    await expect(page.getByRole('alert').filter({ hasText: 'directory service is unavailable' })).toBeVisible();
    await page.evaluate(() => localStorage.removeItem('itsm_admin_simulate_error'));
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(rows(page)).toHaveCount(8);
  });
});

test.describe('admin: technicians', () => {
  test('lists profiles with workload and filters by skill, availability and team', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/technicians');
    await expect(rows(page)).toHaveCount(6);
    await expect(page.getByTestId('pagination-summary')).toHaveText('Showing 1–6 of 6 technicians');
    await page.getByRole('combobox', { name: 'Filter by skill' }).click();
    await page.getByRole('option', { name: 'Cloud' }).click();
    await expect(rows(page)).toHaveCount(2);
    await page.getByRole('combobox', { name: 'Filter by availability' }).click();
    await page.getByRole('option', { name: 'Off shift' }).click();
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Sara Lindqvist');
    await page.getByRole('combobox', { name: 'Filter by team' }).click();
    await page.getByRole('option', { name: 'Security' }).click();
    await expect(page.getByTestId('empty-state')).toBeVisible();
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await expect(rows(page)).toHaveCount(6);
    await search(page, 'networking');
    await expect(rows(page)).toHaveCount(1);
  });

  test('edit validates and persists skills and workload', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/technicians');
    await rowMenu(page, 'Kofi Mensah');
    await page.getByRole('menuitem', { name: 'Edit profile' }).click();
    const dlg = page.getByRole('dialog');
    await dlg.getByRole('button', { name: 'macOS' }).click();
    await dlg.getByRole('button', { name: 'Hardware' }).click();
    await dlg.getByRole('button', { name: 'Audio/Visual' }).click();
    await dlg.getByRole('button', { name: 'Save changes' }).click();
    await expect(dlg.getByText('Select at least one skill')).toBeVisible();

    await dlg.getByRole('button', { name: 'Security' }).click();
    await dlg.getByLabel('Maximum workload').fill('0');
    await dlg.getByRole('button', { name: 'Save changes' }).click();
    await expect(dlg.getByText('whole number from 1 to 20')).toBeVisible();
    await dlg.getByLabel('Maximum workload').fill('1');
    await dlg.getByRole('button', { name: 'Save changes' }).click();
    await expect(dlg.getByRole('alert')).toContainText('cannot be below the current workload');

    await dlg.getByLabel('Maximum workload').fill('10');
    await dlg.getByRole('button', { name: 'Save changes' }).click();
    await toast(page, 'Kofi Mensah’s profile was updated.');
    await page.reload();
    await search(page, 'kofi');
    await expect(rows(page).first()).toContainText('Security');
    await expect(rows(page).first()).toContainText('2 / 10');
  });

  test('quick availability change and workload sort', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/technicians');
    await search(page, 'tom');
    await rowMenu(page, 'Tomás Herrera');
    await page.getByRole('menuitem', { name: 'Available' }).click();
    await toast(page, 'Tomás Herrera is now available.');
    await expect(rows(page).first()).toContainText('Available');
    await page.getByRole('searchbox').fill('');
    await page.getByRole('button', { name: 'Workload' }).click();
    await expect(page.getByRole('columnheader', { name: 'Workload' })).toHaveAttribute('aria-sort', 'descending');
    await expect(rows(page).first()).toContainText('Tomás Herrera'); // 6/6 = 100%
  });

  test('remove profile confirms, then the account can be given a profile again', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/technicians');
    await search(page, 'rahul');
    await rowMenu(page, 'Rahul Verma');
    await page.getByRole('menuitem', { name: 'Remove profile' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Remove profile' }).click();
    await toast(page, /profile was removed/);
    await expect(page.getByTestId('empty-state')).toBeVisible();

    await page.getByRole('button', { name: 'Add profile' }).first().click();
    const dlg = page.getByRole('dialog');
    await dlg.getByRole('button', { name: 'Add profile' }).click();
    await expect(dlg.getByText('Choose the technician account')).toBeVisible();
    await dlg.getByLabel('Technician account').click();
    await page.getByRole('option', { name: /Rahul Verma/ }).click();
    await dlg.getByRole('button', { name: 'Windows' }).click();
    await dlg.getByRole('button', { name: 'Add profile' }).click();
    await toast(page, 'Profile added for Rahul Verma.');
    await expect(rows(page).first()).toContainText('Windows');
  });

  test('add profile with no candidates points to Users; a new technician user gets a profile automatically', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/technicians');
    await page.getByRole('button', { name: 'Add profile' }).first().click();
    await expect(page.getByRole('dialog')).toContainText('Every technician account already has a profile');
    await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).first().click();

    await page.getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'Users' }).click();
    await page.getByRole('button', { name: 'Add user' }).first().click();
    const dlg = page.getByRole('dialog');
    await dlg.getByLabel('Full name').fill('Nora Technician');
    await dlg.getByLabel('Email address').fill('nora.tech@campus.example');
    await dlg.getByLabel('Role').click();
    await page.getByRole('option', { name: 'Technician' }).click();
    await dlg.getByLabel('Department').click();
    await page.getByRole('option', { name: 'Information Technology' }).click();
    await dlg.getByRole('button', { name: 'Add user' }).click();
    await toast(page, 'Nora Technician was invited.');

    await page.getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'Technicians' }).click();
    await expect(page.getByRole('heading', { name: 'Technicians', level: 1 })).toBeVisible();
    await search(page, 'nora');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('None set');
  });

  test('error state offers retry', async ({ page, api }) => {
    await signIn(page, api, newAdmin('tech-err'));
    await page.evaluate(() => localStorage.setItem('itsm_admin_simulate_error', '1'));
    await page.goto('/admin/technicians');
    await expect(page.getByRole('alert').filter({ hasText: 'directory service is unavailable' })).toBeVisible();
    await page.evaluate(() => localStorage.removeItem('itsm_admin_simulate_error'));
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(rows(page)).toHaveCount(6);
  });
});

test.describe('admin: quality', () => {
  for (const path of ['/admin', '/admin/users', '/admin/technicians']) {
    test(`${path} has no axe violations and no console errors`, async ({ page, api }) => {
      await signIn(page, api, newAdmin('axe'));
      const errors = trackErrors(page);
      await page.goto(path);
      if (path !== '/admin') await expect(rows(page).first()).toBeVisible();
      else await expect(page.getByRole('region', { name: 'Key figures' })).toBeVisible();
      await settle(page);
      expect(await axeViolations(page)).toEqual([]);
      expect(errors).toEqual([]);
    });
  }

  test('dialog passes axe', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await page.getByRole('button', { name: 'Add user' }).first().click();
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
  });
});

test.describe('admin: responsive', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  for (const path of ['/admin', '/admin/users', '/admin/technicians']) {
    test(`${path} has no horizontal page overflow and the nav opens as a drawer`, async ({ page, api }) => {
      await signIn(page, api, newAdmin('mobile'));
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await page.waitForLoadState('networkidle');
      await expectNoHorizontalOverflow(page);
      await page.getByRole('button', { name: 'Toggle navigation' }).click();
      const nav = page.getByRole('dialog').getByRole('navigation', { name: 'Admin sections' });
      await expect(nav).toBeVisible();
      await nav.getByRole('link', { name: 'Technicians' }).click();
      await page.waitForURL('**/admin/technicians');
      await expect(page.getByRole('dialog')).toBeHidden();
    });
  }
});
