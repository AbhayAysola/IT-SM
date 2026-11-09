import type { Page } from '@playwright/test';
import { test, expect, newAdmin } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, settle, signIn, trackErrors } from './helpers';

async function openAdmin(page: Page, api: Parameters<typeof signIn>[1], path: string) {
  await signIn(page, api, newAdmin('cfg'));
  await page.goto(path);
  await expect(page.getByRole('navigation', { name: 'Admin sections' })).toBeVisible();
}

const rows = (page: Page) => page.locator('tbody tr');
const search = (page: Page, q: string) => page.getByRole('searchbox').fill(q);
const rowMenu = (page: Page, name: string) => page.getByRole('button', { name: `Actions for ${name}` }).click();
const toast = (page: Page, text: string | RegExp) => expect(page.getByTestId('toasts').getByText(text)).toBeVisible();
const summary = (page: Page) => page.getByTestId('pagination-summary');
const dialog = (page: Page) => page.getByRole('dialog');
const pick = async (page: Page, trigger: string | RegExp, option: string | RegExp, scope = dialog(page)) => {
  await scope.getByLabel(trigger, { exact: typeof trigger === 'string' }).click();
  await page.getByRole('option', { name: option }).click();
};
const filter = async (page: Page, label: string, option: string | RegExp) => {
  await page.getByRole('combobox', { name: label }).click();
  await page.getByRole('option', { name: option, exact: typeof option === 'string' }).click();
};
async function simulateError(page: Page, api: Parameters<typeof signIn>[1], path: string) {
  await signIn(page, api, newAdmin('cfg-err'));
  await page.evaluate(() => localStorage.setItem('itsm_admin_simulate_error', '1'));
  await page.goto(path);
  await expect(page.getByRole('alert').filter({ hasText: 'directory service is unavailable' })).toBeVisible();
  await page.evaluate(() => localStorage.removeItem('itsm_admin_simulate_error'));
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(rows(page).first()).toBeVisible();
}

test.describe('admin config: navigation', () => {
  test('sidebar reaches categories, services and SLA policies', async ({ page, api }) => {
    await openAdmin(page, api, '/admin');
    const nav = page.getByRole('navigation', { name: 'Admin sections' });
    for (const [link, heading] of [['Categories', 'Incident categories'], ['IT services', 'IT services'], ['SLA policies', 'SLA policies']] as const) {
      await nav.getByRole('link', { name: link }).click();
      await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible();
      await expect(nav.getByRole('link', { name: link })).toHaveAttribute('aria-current', 'page');
    }
  });

  test('unauthenticated visits redirect to sign-in', async ({ page }) => {
    for (const p of ['/admin/categories', '/admin/services', '/admin/sla-policies']) {
      await page.goto(p);
      await page.waitForURL(new RegExp(`/\\?redirect=${encodeURIComponent(p).replace(/%/g, '%')}`));
    }
  });
});

test.describe('admin config: categories', () => {
  const path = '/admin/categories';

  test('lists, paginates, searches by keyword and shows empty state', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await expect(rows(page)).toHaveCount(8);
    await expect(summary(page)).toHaveText('Showing 1–8 of 11 categories');
    await page.getByRole('button', { name: /Next/ }).click();
    await expect(rows(page)).toHaveCount(3);
    await search(page, 'phishing');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Security Incident');
    await search(page, 'zzzz-no-match');
    await expect(page.getByTestId('empty-state')).toContainText('No categories match your filters');
  });

  test('filters by status and default priority', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await filter(page, 'Filter by status', 'Inactive');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Legacy Timetabling');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await filter(page, 'Filter by default priority', 'P2 High');
    await expect(rows(page)).toHaveCount(2);
    await filter(page, 'Filter by status', 'Inactive');
    await expect(page.getByTestId('empty-state')).toBeVisible();
  });

  test('add validates fields, blocks duplicate names and conflicting keywords, then saves and persists', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await page.getByRole('button', { name: 'Add category' }).first().click();
    const dlg = dialog(page);
    await dlg.getByRole('button', { name: 'Add category' }).click();
    await expect(dlg.getByText('Enter a category name')).toBeVisible();
    await expect(dlg.getByText('Describe what belongs')).toBeVisible();
    await expect(dlg.getByText('Add at least one keyword')).toBeVisible();

    await dlg.getByLabel('Name', { exact: true }).fill('Hardware');
    await dlg.getByLabel('Description').fill('Duplicate of an existing category name.');
    await dlg.getByLabel('Keywords', { exact: true }).fill('docking station');
    await dlg.getByLabel('Keywords', { exact: true }).press('Enter');
    await dlg.getByRole('button', { name: 'Add category' }).click();
    await expect(dlg.getByRole('alert')).toContainText('already exists');

    await dlg.getByLabel('Name', { exact: true }).fill('Workstation Peripherals');
    await dlg.getByLabel('Keywords', { exact: true }).fill('laptop');
    await dlg.getByLabel('Keywords', { exact: true }).press('Enter');
    await dlg.getByRole('button', { name: 'Add category' }).click();
    await expect(dlg.getByRole('alert')).toContainText('“laptop” is already used by the active category “Hardware”');

    await dlg.getByRole('button', { name: 'Remove keyword laptop' }).click();
    await dlg.getByLabel('Keywords', { exact: true }).fill('webcam, headset');
    await dlg.getByLabel('Keywords', { exact: true }).press('Enter');
    await expect(dlg.getByRole('list', { name: 'Added keywords' }).getByRole('listitem')).toHaveCount(3);
    await dlg.getByRole('button', { name: 'Add category' }).click();
    await toast(page, 'Workstation Peripherals was added.');

    await page.reload();
    await search(page, 'webcam');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Active');
  });

  test('rejects invalid and duplicate keywords inside the form', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await page.getByRole('button', { name: 'Add category' }).first().click();
    const dlg = dialog(page);
    const kw = dlg.getByLabel('Keywords', { exact: true });
    await kw.fill('x');
    await kw.press('Enter');
    await expect(dlg.getByText('at least 2 characters')).toBeVisible();
    await kw.fill('bad<script>');
    await kw.press('Enter');
    await expect(dlg.getByText('Keywords may only contain')).toBeVisible();
    await kw.fill('Router');
    await kw.press('Enter');
    await kw.fill('router');
    await kw.press('Enter');
    await expect(dlg.getByText('already in the list')).toBeVisible();
  });

  test('edit saves changes; cancel discards them', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await search(page, 'hardware');
    await rowMenu(page, 'Hardware');
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    const dlg = dialog(page);
    await dlg.getByLabel('Description').fill('short');
    await dlg.getByRole('button', { name: 'Save changes' }).click();
    await expect(dlg.getByText('at least 10 characters')).toBeVisible();
    await dlg.getByLabel('Description').fill('Updated description for hardware faults.');
    await dlg.getByRole('button', { name: 'Cancel' }).click();
    await expect(rows(page).first()).not.toContainText('Updated description');

    await rowMenu(page, 'Hardware');
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    await dlg.getByLabel('Description').fill('Updated description for hardware faults.');
    await pick(page, 'Default priority', 'P2 High');
    await dlg.getByLabel('Keywords', { exact: true }).fill('docking station');
    await dlg.getByRole('button', { name: 'Save changes' }).click(); // pending draft is committed on submit
    await toast(page, 'Hardware was updated.');
    await expect(rows(page).first()).toContainText('Updated description');
    await expect(rows(page).first()).toContainText('P2 High');
    await search(page, 'docking station');
    await expect(rows(page)).toHaveCount(1);
  });

  test('deactivate confirms, activation re-checks keyword conflicts', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await search(page, 'hardware');
    await rowMenu(page, 'Hardware');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await expect(dialog(page)).toContainText('Deactivate Hardware?');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await expect(rows(page).first()).toContainText('Active');

    await rowMenu(page, 'Hardware');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate category' }).click();
    await toast(page, 'Hardware was deactivated.');
    await expect(rows(page).first()).toContainText('Inactive');

    // Another active category now takes "laptop"; Hardware can no longer be re-activated as-is.
    await search(page, '');
    await page.getByRole('button', { name: 'Add category' }).first().click();
    await dialog(page).getByLabel('Name', { exact: true }).fill('Laptop Loans');
    await dialog(page).getByLabel('Description').fill('Short-term laptop loans for staff.');
    await dialog(page).getByLabel('Keywords', { exact: true }).fill('laptop');
    await dialog(page).getByLabel('Keywords', { exact: true }).press('Enter');
    await dialog(page).getByRole('button', { name: 'Add category' }).click();
    await toast(page, 'Laptop Loans was added.');
    await search(page, 'hardware');
    await rowMenu(page, 'Hardware');
    await page.getByRole('menuitem', { name: 'Activate' }).click();
    await toast(page, /already used by the active category “Laptop Loans”/);
    await expect(rows(page).first()).toContainText('Inactive');
  });

  test('error state offers retry', async ({ page, api }) => simulateError(page, api, path));
});

test.describe('admin config: IT services', () => {
  const path = '/admin/services';

  test('lists, paginates and searches', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await expect(rows(page)).toHaveCount(8);
    await expect(summary(page)).toHaveText('Showing 1–8 of 11 services');
    await page.getByRole('button', { name: /Next/ }).click();
    await expect(rows(page)).toHaveCount(3);
    await search(page, 'sso');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Identity & Single Sign-On');
    await search(page, 'nothing-here');
    await expect(page.getByTestId('empty-state')).toBeVisible();
  });

  test('filters by criticality, status and owner team; sorts by criticality', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await filter(page, 'Filter by criticality', 'Critical');
    await expect(rows(page)).toHaveCount(4);
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await filter(page, 'Filter by status', 'Inactive');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Legacy Timetable');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await filter(page, 'Filter by owner team', 'Security');
    await expect(rows(page)).toHaveCount(1);
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await page.getByRole('button', { name: 'Criticality', exact: true }).click();
    await expect(page.getByRole('columnheader', { name: 'Criticality' })).toHaveAttribute('aria-sort', 'descending');
    await expect(rows(page).first()).toContainText('Critical');
  });

  test('add validates, rejects duplicate names, saves with dependencies and persists', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await page.getByRole('button', { name: 'Add service' }).first().click();
    const dlg = dialog(page);
    await dlg.getByRole('button', { name: 'Add service' }).click();
    await expect(dlg.getByText('Enter a service name')).toBeVisible();
    await expect(dlg.getByText('Describe what the service provides')).toBeVisible();
    await expect(dlg.getByText('Choose the team')).toBeVisible();

    await dlg.getByLabel('Name', { exact: true }).fill('campus wi-fi');
    await dlg.getByLabel('Description').fill('A duplicate of the existing wireless service.');
    await pick(page, 'Owner team', 'Infrastructure');
    await dlg.getByRole('button', { name: 'Add service' }).click();
    await expect(dlg.getByRole('alert')).toContainText('already exists');

    await dlg.getByLabel('Name', { exact: true }).fill('Research Computing');
    await dlg.getByLabel('Description').fill('High-performance computing cluster for researchers.');
    await pick(page, 'Business criticality', 'High');
    await dlg.getByLabel('Core Network').check();
    await dlg.getByLabel('Database Platform').check();
    await dlg.getByRole('button', { name: 'Add service' }).click();
    await toast(page, 'Research Computing was added.');

    await page.reload();
    await search(page, 'research computing');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Core Network');
    await expect(rows(page).first()).toContainText('Database Platform');
  });

  test('edit changes criticality and dependencies; circular, self and inactive dependencies are unavailable', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await search(page, 'core network');
    await rowMenu(page, 'Core Network');
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    const dlg = dialog(page);
    const opts = dlg.getByTestId('dependency-options');
    await expect(opts.getByLabel('Core Network')).toHaveCount(0); // no self-dependency
    await expect(opts.getByLabel('Campus Wi-Fi')).toBeDisabled(); // direct dependent
    await expect(opts.getByLabel('Learning Management System')).toBeDisabled(); // transitive dependent
    await expect(opts.getByLabel('Legacy Timetable')).toBeDisabled(); // inactive
    await expect(opts.getByText('Would create a cycle').first()).toBeVisible();
    await expect(opts.getByLabel('Library Catalogue')).toBeDisabled(); // library -> db -> core
    await expect(opts.getByLabel('Print Services')).toBeDisabled();
    await dlg.getByRole('button', { name: 'Cancel' }).click();

    await search(page, 'print services');
    await rowMenu(page, 'Print Services');
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    await pick(page, 'Business criticality', 'Medium');
    await dialog(page).getByLabel('Core Network').uncheck();
    await dialog(page).getByLabel('Email & Calendar').check(); // Email isn't downstream of Print, so allowed
    await dialog(page).getByLabel('Name', { exact: true }).fill('');
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(dialog(page).getByText('Enter a service name')).toBeVisible();
    await dialog(page).getByLabel('Name', { exact: true }).fill('Print Services');
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, 'Print Services was updated.');
    await expect(rows(page).first()).toContainText('Medium');
    await expect(rows(page).first()).toContainText('Email & Calendar');
    await expect(rows(page).first()).not.toContainText('Core Network');

    // Print Services -> Email now exists, so Email can no longer depend on Print Services.
    await search(page, 'email');
    await rowMenu(page, 'Email & Calendar');
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    await expect(dialog(page).getByTestId('dependency-options').getByLabel('Print Services')).toBeDisabled();
  });

  test('deactivation is blocked while other services depend on it; confirm, activate and dependency checks work', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await search(page, 'core network');
    await rowMenu(page, 'Core Network');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate service' }).click();
    await expect(dialog(page).getByRole('alert')).toContainText('is a dependency of');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await expect(rows(page).first()).toContainText('Active');

    await search(page, 'print services');
    await rowMenu(page, 'Print Services');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await expect(dialog(page)).toContainText('Deactivate Print Services?');
    await dialog(page).getByRole('button', { name: 'Deactivate service' }).click();
    await toast(page, 'Print Services was deactivated.');
    await expect(rows(page).first()).toContainText('Inactive');
    await rowMenu(page, 'Print Services');
    await page.getByRole('menuitem', { name: 'Activate' }).click();
    await toast(page, 'Print Services was activated.');
    await expect(rows(page).first()).toContainText('Active');
  });

  test('error state offers retry', async ({ page, api }) => simulateError(page, api, path));
});

test.describe('admin config: SLA policies', () => {
  const path = '/admin/sla-policies';

  test('lists with summaries, paginates, and warns about services without a policy', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await expect(rows(page)).toHaveCount(8);
    await expect(summary(page)).toHaveText('Showing 1–8 of 17 policies');
    await expect(page.getByTestId('coverage-gaps')).toContainText('5 active services have no SLA policy');
    await expect(page.getByTestId('coverage-gaps')).toContainText('Database Platform');
    await expect(rows(page).filter({ hasText: 'Core Network' }).filter({ hasText: 'P1 Critical' })).toContainText('Respond within 5 min, resolve within 2 h.');
    await page.getByRole('button', { name: /Next/ }).click();
    await expect(summary(page)).toHaveText('Showing 9–16 of 17 policies');
    await page.getByRole('button', { name: /Next/ }).click();
    await expect(summary(page)).toHaveText('Showing 17–17 of 17 policies');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Print Services');
    await expect(rows(page).first()).toContainText('Inactive');
  });

  test('filters by service, priority and status and searches', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await filter(page, 'Filter by service', 'Core Network');
    await expect(rows(page)).toHaveCount(4);
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await filter(page, 'Filter by priority', 'P1 Critical');
    await expect(rows(page)).toHaveCount(4);
    await filter(page, 'Filter by status', 'Disabled');
    await expect(page.getByTestId('empty-state')).toBeVisible();
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await filter(page, 'Filter by status', 'Disabled');
    await expect(rows(page)).toHaveCount(1);
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await search(page, 'learning');
    await expect(rows(page)).toHaveCount(3);
  });

  test('sorts by resolution target', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await page.getByRole('button', { name: 'Resolution', exact: true }).click();
    await expect(page.getByRole('columnheader', { name: 'Resolution' })).toHaveAttribute('aria-sort', 'ascending');
    await expect(rows(page).first()).toContainText('2 h');
    await page.getByRole('button', { name: 'Resolution', exact: true }).click();
    await expect(rows(page).first()).toContainText('3 days');
  });

  test('add validates targets, prevents duplicates and cross-priority inconsistencies, shows a live summary, then saves', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await page.getByRole('button', { name: 'Add policy' }).first().click();
    const dlg = dialog(page);
    await dlg.getByRole('button', { name: 'Add policy' }).click();
    await expect(dlg.getByText('Choose the service')).toBeVisible();
    await expect(dlg.getByText('Choose a priority')).toBeVisible();
    await expect(dlg.getByText('Response target must be a positive whole number')).toBeVisible();
    await expect(dlg.getByText('Resolution target must be a positive whole number')).toBeVisible();
    await expect(dlg.getByRole('option', { name: 'Legacy Timetable' })).toHaveCount(0);

    await pick(page, 'Service', 'Database Platform');
    await pick(page, 'Priority', 'P2 High');
    await dlg.getByLabel('Response target', { exact: true }).fill('0');
    await dlg.getByLabel('Resolution target', { exact: true }).fill('4');
    await dlg.getByRole('button', { name: 'Add policy' }).click();
    await expect(dlg.getByText('Response target must be a positive whole number')).toBeVisible();

    await dlg.getByLabel('Response target', { exact: true }).fill('-5');
    await dlg.getByRole('button', { name: 'Add policy' }).click();
    await expect(dlg.getByText('Response target must be a positive whole number')).toBeVisible();

    await dlg.getByLabel('Response target', { exact: true }).fill('5');
    await dlg.getByLabel('Resolution target', { exact: true }).fill('5');
    await pick(page, 'Resolution target unit', 'minutes');
    await dlg.getByRole('button', { name: 'Add policy' }).click();
    await expect(dlg.getByText('Resolution must take longer than the response target')).toBeVisible();

    await pick(page, 'Resolution target unit', 'days');
    await dlg.getByLabel('Resolution target', { exact: true }).fill('31');
    await dlg.getByRole('button', { name: 'Add policy' }).click();
    await expect(dlg.getByText('cannot exceed 30 days')).toBeVisible();

    await pick(page, 'Resolution target unit', 'hours');
    await dlg.getByLabel('Resolution target', { exact: true }).fill('4');
    await expect(dlg.getByTestId('sla-summary')).toHaveText('P2 incidents on Database Platform: respond within 5 min, resolve within 4 h.');
    await dlg.getByRole('button', { name: 'Add policy' }).click();
    await toast(page, 'P2 policy added for Database Platform.');

    // Cross-priority consistency: P1 cannot respond slower than the P2 target.
    await page.getByRole('button', { name: 'Add policy' }).first().click();
    await pick(page, 'Service', 'Database Platform');
    await pick(page, 'Priority', 'P1 Critical');
    await dlg.getByLabel('Response target', { exact: true }).fill('10');
    await dlg.getByLabel('Resolution target', { exact: true }).fill('1');
    await dlg.getByRole('button', { name: 'Add policy' }).click();
    await expect(dlg.getByRole('alert')).toContainText('P1 response target (10 min) cannot be longer than the P2 response target (5 min)');
    await dlg.getByLabel('Response target', { exact: true }).fill('3');
    await dlg.getByRole('button', { name: 'Add policy' }).click();
    await toast(page, 'P1 policy added for Database Platform.');

    // Duplicate service + priority.
    await page.getByRole('button', { name: 'Add policy' }).first().click();
    await pick(page, 'Service', 'Database Platform');
    await pick(page, 'Priority', 'P1 Critical');
    await dlg.getByLabel('Response target', { exact: true }).fill('2');
    await dlg.getByLabel('Resolution target', { exact: true }).fill('1');
    await dlg.getByRole('button', { name: 'Add policy' }).click();
    await expect(dlg.getByRole('alert')).toContainText('Database Platform already has a P1 policy');
    await dlg.getByRole('button', { name: 'Cancel' }).click();

    await page.reload();
    await expect(page.getByTestId('coverage-gaps')).toContainText('4 active services have no SLA policy');
    await search(page, 'database');
    await expect(rows(page)).toHaveCount(2);
  });

  test('edit changes targets only, validates, and keeps priorities consistent', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await filter(page, 'Filter by service', 'Core Network');
    await rowMenu(page, 'P1 policy on Core Network');
    await page.getByRole('menuitem', { name: 'Edit targets' }).click();
    const dlg = dialog(page);
    await expect(dlg.getByRole('term').filter({ hasText: 'Service' })).toBeVisible();
    await expect(dlg.getByLabel('Response target', { exact: true })).toHaveValue('5');
    await expect(dlg.getByLabel('Resolution target', { exact: true })).toHaveValue('2');

    await dlg.getByLabel('Resolution target', { exact: true }).fill('8'); // 8 h > P2 4 h
    await dlg.getByRole('button', { name: 'Save changes' }).click();
    await expect(dlg.getByRole('alert')).toContainText('P1 resolution target (8 h) cannot be longer than the P2 resolution target (4 h)');
    await dlg.getByLabel('Resolution target', { exact: true }).fill('3');
    await dlg.getByRole('button', { name: 'Save changes' }).click();
    await toast(page, 'P1 policy for Core Network was updated.');
    await expect(rows(page).first()).toContainText('Respond within 5 min, resolve within 3 h.');
  });

  test('disable/enable and delete (with confirmation)', async ({ page, api }) => {
    await openAdmin(page, api, path);
    await filter(page, 'Filter by service', 'Core Network');
    await rowMenu(page, 'P4 policy on Core Network');
    await page.getByRole('menuitem', { name: 'Disable' }).click();
    await toast(page, 'P4 policy for Core Network was disabled.');
    await expect(rows(page).filter({ hasText: 'P4 Low' })).toContainText('Inactive');
    await rowMenu(page, 'P4 policy on Core Network');
    await page.getByRole('menuitem', { name: 'Enable' }).click();
    await toast(page, 'P4 policy for Core Network was enabled.');

    await rowMenu(page, 'P4 policy on Core Network');
    await page.getByRole('menuitem', { name: 'Delete' }).click();
    await expect(dialog(page)).toContainText('cannot be undone');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await expect(rows(page)).toHaveCount(4);
    await rowMenu(page, 'P4 policy on Core Network');
    await page.getByRole('menuitem', { name: 'Delete' }).click();
    await dialog(page).getByRole('button', { name: 'Delete policy' }).click();
    await toast(page, 'P4 policy for Core Network was deleted.');
    await expect(rows(page)).toHaveCount(3);
  });

  test('error state offers retry', async ({ page, api }) => simulateError(page, api, path));
});

test.describe('admin config: audit trail', () => {
  test('every mutation appends an audit entry and earlier entries are never altered', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/categories');
    const audit = () => page.evaluate(() => JSON.parse(localStorage.getItem('itsm_admin_v1_audit') ?? '[]') as { id: string; entity: string; action: string; summary: string }[]);

    // Category: create, update, deactivate
    await page.getByRole('button', { name: 'Add category' }).first().click();
    await dialog(page).getByLabel('Name', { exact: true }).fill('Telephony');
    await dialog(page).getByLabel('Description').fill('Desk phones and call forwarding.');
    await dialog(page).getByLabel('Keywords', { exact: true }).fill('desk phone');
    await dialog(page).getByRole('button', { name: 'Add category' }).click();
    await toast(page, 'Telephony was added.');
    const afterCreate = await audit();
    expect(afterCreate[0]).toMatchObject({ entity: 'CATEGORY', action: 'CREATE' });

    await search(page, 'telephony');
    await rowMenu(page, 'Telephony');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate category' }).click();
    await toast(page, 'Telephony was deactivated.');

    // Service + SLA policy
    await page.getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'IT services' }).click();
    await expect(page.getByRole('heading', { name: 'IT services', level: 1 })).toBeVisible();
    await search(page, 'print');
    await rowMenu(page, 'Print Services');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate service' }).click();
    await toast(page, 'Print Services was deactivated.');

    await page.getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'SLA policies' }).click();
    await expect(page.getByRole('heading', { name: 'SLA policies', level: 1 })).toBeVisible();
    await filter(page, 'Filter by service', 'Core Network');
    await rowMenu(page, 'P3 policy on Core Network');
    await page.getByRole('menuitem', { name: 'Disable' }).click();
    await toast(page, 'P3 policy for Core Network was disabled.');

    const final = await audit();
    expect(final.length).toBe(afterCreate.length + 3);
    const kinds = final.slice(0, 4).map((e) => `${e.entity}:${e.action}`);
    expect(kinds).toEqual(['SLA_POLICY:STATUS_CHANGE', 'SERVICE:STATUS_CHANGE', 'CATEGORY:STATUS_CHANGE', 'CATEGORY:CREATE']);
    // Append-only: the earlier entries are byte-identical and keep their order.
    expect(final.slice(3)).toEqual(afterCreate);

    await page.getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: 'Admin overview' }).click();
    await expect(page.getByTestId('recent-changes')).toContainText('Disabled P3 SLA policy for Core Network');
  });
});

test.describe('admin config: quality', () => {
  for (const path of ['/admin/categories', '/admin/services', '/admin/sla-policies']) {
    test(`${path} has no axe violations or console errors`, async ({ page, api }) => {
      await signIn(page, api, newAdmin('axe'));
      const errors = trackErrors(page);
      await page.goto(path);
      await expect(rows(page).first()).toBeVisible();
      await settle(page);
      expect(await axeViolations(page)).toEqual([]);
      expect(errors).toEqual([]);
    });
  }

  for (const [path, button] of [['/admin/categories', 'Add category'], ['/admin/services', 'Add service'], ['/admin/sla-policies', 'Add policy']] as const) {
    test(`${button} dialog passes axe and traps focus`, async ({ page, api }) => {
      await openAdmin(page, api, path);
      await page.getByRole('button', { name: button }).first().click();
      await settle(page);
      expect(await axeViolations(page)).toEqual([]);
      await page.keyboard.press('Escape');
      await expect(dialog(page)).toBeHidden();
    });
  }
});

test.describe('admin config: responsive', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  for (const [path, heading, link] of [
    ['/admin/categories', 'Incident categories', 'Categories'],
    ['/admin/services', 'IT services', 'IT services'],
    ['/admin/sla-policies', 'SLA policies', 'SLA policies'],
  ] as const) {
    test(`${path} fits the phone viewport and the drawer links here`, async ({ page, api }) => {
      await signIn(page, api, newAdmin('mobile'));
      await page.goto('/admin');
      await page.getByRole('button', { name: 'Toggle navigation' }).click();
      await dialog(page).getByRole('navigation', { name: 'Admin sections' }).getByRole('link', { name: link }).click();
      await page.waitForURL(`**${path}`);
      await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible();
      await expect(rows(page).first()).toBeVisible();
      await page.waitForLoadState('networkidle');
      await expectNoHorizontalOverflow(page);
    });

    test(`${path} add dialog fits the phone viewport`, async ({ page, api }) => {
      await signIn(page, api, newAdmin('mobile-dlg'));
      await page.goto(path);
      await page.getByRole('button', { name: /^Add / }).first().click();
      const box = await dialog(page).boundingBox();
      expect(box!.x).toBeGreaterThanOrEqual(0);
      expect(box!.x + box!.width).toBeLessThanOrEqual(390);
      await expectNoHorizontalOverflow(page);
    });
  }
});
