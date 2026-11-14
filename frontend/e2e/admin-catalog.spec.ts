import { test, expect } from './fixtures';
import { dialog, openAdmin, pick, rowFor, rowMenu, rows, search, summary, toast } from './admin-helpers';

test.describe('admin: categories', () => {
  const PATH = '/admin/categories';

  test('lists categories with code, keywords and status; no priority is shown or sent', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await expect(rows(page)).toHaveCount(3);
    const row = rows(page).filter({ hasText: 'Network' });
    await expect(row).toContainText('NETWORK');
    await expect(row).toContainText('vpn');
    await expect(row).toContainText('Active');
    expect((await page.locator('main').innerText())).not.toMatch(/priority/i);
    const q = api.admin.last('GET', '/api/admin/categories')!.query;
    expect([q.get('page'), q.get('size'), q.get('sort')]).toEqual(['0', '10', 'name']);
  });

  test('search, status filter and sort are sent to the API', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await search(page, 'outlook');
    await expect(rows(page)).toHaveCount(1);
    await expect.poll(() => api.admin.last('GET', '/api/admin/categories')?.query.get('search')).toBe('outlook');
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await pick(page, 'Filter by status', 'Inactive');
    await expect(page.getByText('No categories match your filters')).toBeVisible();
    await expect.poll(() => api.admin.last('GET', '/api/admin/categories')?.query.get('active')).toBe('false');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await page.getByRole('button', { name: 'Code', exact: true }).click();
    // Nothing on screen changes until the re-sorted response lands, so poll the recorded requests
    // instead of reading the latest one immediately after the click.
    await expect.poll(() => api.admin.last('GET', '/api/admin/categories')?.query.get('sort')).toBe('code');
  });

  test('creating validates the code and keywords before sending, then sends normalised keywords', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Add category' }).first().click();
    await dialog(page).getByRole('button', { name: 'Add category' }).click();
    await expect(dialog(page).getByText('Enter a code.')).toBeVisible();
    await expect(dialog(page).getByText('Use 3–60 characters.')).toBeVisible();
    await expect(dialog(page).getByText('Add at least one keyword.')).toBeVisible();
    expect(api.admin.count('POST', '/api/admin/categories')).toBe(0);
    await dialog(page).getByLabel('Code').fill('bad code');
    await dialog(page).getByLabel('Name').fill('Printing');
    await dialog(page).getByLabel('Keywords').fill('Printer,  PRINTER ');
    await dialog(page).getByRole('button', { name: 'Add category' }).click();
    await expect(dialog(page).getByText(/UPPER_SNAKE_CASE/).first()).toBeVisible();
    await expect(dialog(page).getByText('“printer” is listed more than once.')).toBeVisible();
    await dialog(page).getByLabel('Code').fill('printing');
    await dialog(page).getByLabel('Keywords').fill('Print Queue,  Toner\nScanner');
    await dialog(page).getByRole('button', { name: 'Add category' }).click();
    await toast(page, 'Category “Printing” was added.');
    expect(api.admin.last('POST', '/api/admin/categories')!.body).toEqual({ code: 'PRINTING', name: 'Printing', description: null, keywords: ['print queue', 'toner', 'scanner'] });
    await expect(rows(page).filter({ hasText: 'PRINTING' })).toContainText('print queue');
  });

  test('a duplicate code is explained by the server and the typed values stay', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Add category' }).first().click();
    await dialog(page).getByLabel('Code').fill('NETWORK');
    await dialog(page).getByLabel('Name').fill('Networking 2');
    await dialog(page).getByLabel('Keywords').fill('router');
    await dialog(page).getByRole('button', { name: 'Add category' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText("A category with code 'NETWORK' already exists");
    await expect(dialog(page).getByLabel('Keywords')).toHaveValue('router');
  });

  test('the code is immutable when editing; a stale version keeps my edits and retries on the latest', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await rowMenu(page, 'Hardware');
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    await expect(dialog(page).getByLabel('Code')).toBeDisabled();
    api.admin.bump('categories', 2);
    await dialog(page).getByLabel('Keywords').fill('laptop, monitor, dock');
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText('changed by someone else');
    await dialog(page).getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, 'Category “Hardware” was updated.');
    const body = api.admin.last('PUT', '/api/admin/categories/2')!.body as Record<string, unknown>;
    expect(body.version).toBe(1);
    expect(body).not.toHaveProperty('code');
    expect(body.keywords).toEqual(['laptop', 'monitor', 'dock']);
  });

  test('activation takes no version, and the server’s refusal to deactivate the last active category is shown', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await rowMenu(page, 'Email');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate category' }).click();
    await toast(page, 'Category “Email” was deactivated.');
    expect(api.admin.last('POST', '/api/admin/categories/3/deactivate')!.body).toBeUndefined();
    await rowMenu(page, 'Hardware');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate category' }).click();
    await toast(page, 'Category “Hardware” was deactivated.');
    await rowMenu(page, 'Network');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate category' }).click();
    await expect(dialog(page)).toContainText('At least one category must stay active');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await rowMenu(page, 'Email');
    await page.getByRole('menuitem', { name: 'Activate' }).click();
    await dialog(page).getByRole('button', { name: 'Activate category' }).click();
    await toast(page, 'Category “Email” was activated.');
  });

  test('empty state, failure and retry', async ({ page, api }) => {
    api.admin.failures.push({ match: 'GET /api/admin/categories', status: 500, code: 'INTERNAL_ERROR', detail: 'secret stack', times: 1 });
    await openAdmin(page, api, PATH);
    await expect(page.getByText('secret stack')).toHaveCount(0);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(rows(page).first()).toBeVisible();
    api.admin.categories = [];
    await page.reload();
    await expect(page.getByText('No categories yet')).toBeVisible();
  });
});

test.describe('admin: IT services', () => {
  const PATH = '/admin/services';

  test('lists services with numeric criticality, dependencies and dependents from the API', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await expect(rows(page)).toHaveCount(3);
    await expect(summary(page)).toHaveText('Showing 1–3 of 3 services');
    await expect(rowFor(page, 'VPN')).toContainText('5 · Mission critical');
    await expect(rowFor(page, 'Intranet').locator('td').nth(2)).toContainText('VPN'); // depends on
    await expect(rowFor(page, 'VPN').locator('td').nth(3)).toContainText('Intranet'); // depended on by
    await expect(rowFor(page, 'Intranet')).toContainText('No owner team');
  });

  test('criticality, status and search filters are sent to the API', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await pick(page, 'Filter by criticality', '5 · Mission critical');
    await expect(rows(page)).toHaveCount(1);
    await expect.poll(() => api.admin.last('GET', '/api/admin/services')?.query.get('criticality')).toBe('5');
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await search(page, 'messag');
    // The criticality filter also left exactly one row (VPN), so a row count alone would pass before the
    // debounced search lands; wait for the row that only the search returns.
    await expect(rowFor(page, 'Email')).toBeVisible();
    await expect(rows(page)).toHaveCount(1);
    await expect.poll(() => api.admin.last('GET', '/api/admin/services')?.query.get('search')).toBe('messag');
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await page.getByRole('button', { name: 'Criticality' }).click();
    // See the categories test: the sort request is sent asynchronously after the click.
    await expect.poll(() => api.admin.last('GET', '/api/admin/services')?.query.get('sort')).toBe('businessCriticality');
  });

  test('creating sends businessCriticality as a number and the chosen dependency ids', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Add service' }).first().click();
    await dialog(page).getByRole('button', { name: 'Add service' }).click();
    await expect(dialog(page).getByText('Use 3–60 characters.')).toBeVisible();
    expect(api.admin.count('POST', '/api/admin/services')).toBe(0);
    await dialog(page).getByLabel('Name').fill('Payroll');
    await dialog(page).getByLabel('Owner team (optional)').fill('Finance IT');
    await pick(page, 'Business criticality', '4 · High');
    await dialog(page).getByLabel('VPN').check();
    await dialog(page).getByRole('button', { name: 'Add service' }).click();
    await toast(page, 'Service “Payroll” was added.');
    expect(api.admin.last('POST', '/api/admin/services')!.body).toEqual({
      name: 'Payroll', description: null, businessCriticality: 4, ownerTeam: 'Finance IT', dependsOnServiceIds: [2],
    });
  });

  test('a duplicate name and a dependency cycle are explained by the server', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Add service' }).first().click();
    await dialog(page).getByLabel('Name').fill('email');
    await dialog(page).getByRole('button', { name: 'Add service' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText("An IT service named 'email' already exists");
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    // VPN <- Intranet already; making VPN depend on Intranet would close the loop
    await rowMenu(page, 'VPN');
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    await expect(dialog(page).getByLabel('VPN')).toHaveCount(0); // never offered as its own dependency
    await dialog(page).getByLabel('Intranet').check();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText('would create a cycle');
    await expect(dialog(page).getByLabel('Intranet')).toBeChecked();
  });

  test('editing sends the version; stale versions are recoverable', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await rowMenu(page, 'Email');
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    api.admin.bump('services', 1);
    await dialog(page).getByLabel('Owner team (optional)').fill('Messaging Ops');
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText('changed by someone else');
    await dialog(page).getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, 'Service “Email” was updated.');
    expect((api.admin.last('PUT', '/api/admin/services/1')!.body as { version: number }).version).toBe(1);
  });

  test('deactivation is soft and refused while active services depend on it', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await rowMenu(page, 'VPN');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate service' }).click();
    await expect(dialog(page)).toContainText('Active services still depend on this one: Intranet');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await rowMenu(page, 'Intranet');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate service' }).click();
    await toast(page, 'Service “Intranet” was deactivated.');
    await expect(rowFor(page, 'Intranet')).toContainText('Inactive');
    await expect(page.getByRole('menuitem', { name: /delete/i })).toHaveCount(0);
  });

  test('empty state and failures', async ({ page, api }) => {
    api.admin.failures.push({ match: 'GET /api/admin/services', status: 500, code: 'INTERNAL_ERROR', detail: 'oops', times: 1 });
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(rows(page).first()).toBeVisible();
    api.admin.services = [];
    await page.reload();
    await expect(page.getByText('No IT services yet')).toBeVisible();
  });
});
