import { test, expect, newAdmin } from './fixtures';
import { dialog, openAdmin, pick, rowMenu, rows, toast } from './admin-helpers';

test.describe('admin: SLA policies', () => {
  const PATH = '/admin/sla-policies';

  test('lists global and service policies; filters map to the API', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await expect(rows(page)).toHaveCount(5);
    await expect(rows(page).filter({ hasText: 'Global default' })).toHaveCount(4);
    await expect(rows(page).filter({ hasText: 'VPN' })).toContainText('5 min');
    await pick(page, 'Filter by scope', 'Global only');
    await expect(rows(page)).toHaveCount(4);
    expect(api.admin.last('GET', '/api/admin/sla-policies')!.query.get('global')).toBe('true');
    await pick(page, 'Filter by scope', 'Service-specific only');
    await expect(rows(page)).toHaveCount(1);
    expect(api.admin.last('GET', '/api/admin/sla-policies')!.query.get('global')).toBe('false');
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await pick(page, 'Filter by priority', 'P1 Critical');
    await expect(rows(page)).toHaveCount(2);
    expect(api.admin.last('GET', '/api/admin/sla-policies')!.query.get('priority')).toBe('P1');
    await pick(page, 'Filter by status', 'Inactive');
    await expect(page.getByText('No policies match your filters')).toBeVisible();
  });

  test('creating a global policy sends a null service id; target rules are checked before sending', async ({ page, api }) => {
    api.admin.sla = api.admin.sla.filter((p) => !(p.serviceId === null && p.priority === 'P4'));
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Add policy' }).first().click();
    await pick(page, 'Priority', 'P4 Low');
    await dialog(page).getByLabel('Response target (minutes)').fill('120');
    await dialog(page).getByLabel('Resolution target (minutes)').fill('60');
    await dialog(page).getByRole('button', { name: 'Add policy' }).click();
    await expect(dialog(page).getByText('Must be longer than the response target.')).toBeVisible();
    await dialog(page).getByLabel('Resolution target (minutes)').fill('99999');
    await dialog(page).getByRole('button', { name: 'Add policy' }).click();
    await expect(dialog(page).getByText(/at most 43200 minutes/)).toBeVisible();
    await dialog(page).getByLabel('Warning threshold (%)').fill('100');
    await dialog(page).getByRole('button', { name: 'Add policy' }).click();
    await expect(dialog(page).getByText('Enter a whole number from 1 to 99.')).toBeVisible();
    expect(api.admin.count('POST', '/api/admin/sla-policies')).toBe(0);
    await dialog(page).getByLabel('Resolution target (minutes)').fill('1440');
    await dialog(page).getByLabel('Warning threshold (%)').fill('75');
    await dialog(page).getByRole('button', { name: 'Add policy' }).click();
    await toast(page, 'P4 policy was added.');
    expect(api.admin.last('POST', '/api/admin/sla-policies')!.body).toEqual({ serviceId: null, priority: 'P4', responseMinutes: 120, resolutionMinutes: 1440, warningThresholdPct: 75 });
  });

  test('a service-specific policy sends the service id; a duplicate scope is explained', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Add policy' }).first().click();
    await pick(page, 'Applies to', 'Email');
    await pick(page, 'Priority', 'P2 High');
    await dialog(page).getByLabel('Response target (minutes)').fill('10');
    await dialog(page).getByLabel('Resolution target (minutes)').fill('100');
    await dialog(page).getByRole('button', { name: 'Add policy' }).click();
    await toast(page, 'P2 policy was added.');
    expect((api.admin.last('POST', '/api/admin/sla-policies')!.body as { serviceId: number }).serviceId).toBe(1);
    await page.getByRole('button', { name: 'Add policy' }).first().click();
    await pick(page, 'Applies to', 'Email');
    await pick(page, 'Priority', 'P2 High');
    await dialog(page).getByLabel('Response target (minutes)').fill('10');
    await dialog(page).getByLabel('Resolution target (minutes)').fill('100');
    await dialog(page).getByRole('button', { name: 'Add policy' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText('already exists');
  });

  test('editing keeps service and priority fixed, sends the version, and recovers from a stale one', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await rowMenu(page, 'VPN P1 policy');
    await page.getByRole('menuitem', { name: 'Edit targets' }).click();
    await expect(dialog(page).getByLabel('Applies to')).toBeDisabled();
    await expect(dialog(page).getByLabel('Priority')).toBeDisabled();
    api.admin.bump('sla', 5);
    await dialog(page).getByLabel('Resolution target (minutes)').fill('90');
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText('changed by someone else');
    await dialog(page).getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, 'P1 policy was updated.');
    expect(api.admin.last('PUT', '/api/admin/sla-policies/5')!.body).toEqual({ responseMinutes: 5, resolutionMinutes: 90, warningThresholdPct: 80, version: 1 });
  });

  test('deactivating takes no version and explains the global fallback', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await rowMenu(page, 'VPN P1 policy');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await expect(dialog(page)).toContainText('falls back to its global policy');
    await dialog(page).getByRole('button', { name: 'Deactivate policy' }).click();
    await toast(page, 'P1 policy was deactivated.');
    expect(api.admin.last('POST', '/api/admin/sla-policies/5/deactivate')!.body).toBeUndefined();
  });

  test('the effective preview shows service-specific, then global fallback, and says clearly when nothing applies', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    const card = page.getByTestId('effective-sla');
    await pick(page, 'Service', 'VPN');
    await pick(page, 'Priority', 'P1 Critical');
    await card.getByRole('button', { name: 'Check' }).click();
    await expect(page.getByTestId('effective-result')).toContainText('Service-specific policy');
    await expect(page.getByTestId('effective-result')).toContainText('respond within 5 min');
    await pick(page, 'Priority', 'P3 Medium');
    await card.getByRole('button', { name: 'Check' }).click();
    await expect(page.getByTestId('effective-result')).toContainText('Global policy');
    api.admin.sla = api.admin.sla.filter((p) => !(p.serviceId === null && p.priority === 'P3'));
    await card.getByRole('button', { name: 'Check' }).click();
    await expect(page.getByTestId('effective-result')).toContainText('No policy applies');
    await expect(page.getByTestId('effective-result')).toContainText('No active SLA policy exists');
    expect(api.admin.last('GET', '/api/admin/sla-policies/effective')!.query.get('priority')).toBe('P3');
  });

  test('a service-name search explains that global policies are not matched, and says how to find them', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await expect(page.getByTestId('sla-search-hint')).toHaveCount(0);
    await expect(rows(page).filter({ hasText: 'Global default' })).toHaveCount(4);
    await page.getByRole('searchbox').fill('vpn');
    const hint = page.getByTestId('sla-search-hint');
    await expect(hint).toBeVisible();
    await expect(hint).toContainText('Global policies have no service name');
    await expect(hint).toContainText('not matched by this search');
    await expect(hint).toContainText('Scope → Global only');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).filter({ hasText: 'Global default' })).toHaveCount(0);
    expect(api.admin.last('GET', '/api/admin/sla-policies')!.query.get('search')).toBe('vpn');
    // the way out the hint names really works
    await page.getByRole('searchbox').fill('');
    await pick(page, 'Filter by scope', 'Global only');
    await expect(rows(page)).toHaveCount(4);
    await expect(page.getByTestId('sla-search-hint')).toHaveCount(0);
    // combining a search with Global only gets its own, accurate wording
    await page.getByRole('searchbox').fill('vpn');
    await expect(page.getByTestId('sla-search-hint')).toContainText('no global policy can match a search');
    await expect(rows(page)).toHaveCount(0);
  });

  test('empty state and failures', async ({ page, api }) => {
    api.admin.failures.push({ match: 'GET /api/admin/sla-policies?', status: 500, code: 'INTERNAL_ERROR', detail: 'x', times: 0 });
    api.admin.sla = [];
    await openAdmin(page, api, PATH);
    await expect(page.getByText('No SLA policies yet')).toBeVisible();
  });
});

test.describe('admin: assignment settings', () => {
  const PATH = '/admin/assignment-settings';

  test('shows the real configuration; strategy choices are exactly the registered ones', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await expect(page.getByLabel('Default maximum workload')).toHaveValue('5');
    await page.getByRole('combobox', { name: 'Active strategy' }).click();
    await expect(page.getByRole('option')).toHaveText(['ROUND_ROBIN', 'SKILL_BASED']);
    expect((await page.locator('main').innerText())).not.toMatch(/MANUAL|fallback|team pool/i);
  });

  test('replaces the whole configuration after a confirmation, sending the version and complete overrides', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await expect(page.getByRole('button', { name: 'Save settings' })).toBeDisabled();
    await pick(page, 'Active strategy', 'SKILL_BASED');
    await page.getByLabel('Default maximum workload').fill('7');
    await page.getByRole('button', { name: 'Add override' }).click();
    await pick(page, /Strategy for Email|Strategy for Hardware|Strategy for Network/, 'SKILL_BASED');
    await page.getByRole('button', { name: 'Save settings' }).click();
    await expect(dialog(page)).toContainText('replaces the whole configuration');
    expect(api.admin.count('PUT', '/api/admin/assignment-settings')).toBe(0);
    await dialog(page).getByRole('button', { name: 'Save settings' }).click();
    await toast(page, 'Assignment settings were saved.');
    const body = api.admin.last('PUT', '/api/admin/assignment-settings')!.body as Record<string, unknown>;
    expect(body.activeStrategy).toBe('SKILL_BASED');
    expect(body.defaultMaxWorkload).toBe(7);
    expect(body.version).toBe(0);
    expect(body.categoryOverrides).toHaveLength(1);
    await expect(page.getByRole('button', { name: 'Save settings' })).toBeDisabled();
    await page.reload();
    await expect(page.getByLabel('Default maximum workload')).toHaveValue('7');
    await expect(page.getByTestId('override-row')).toHaveCount(1);
  });

  test('workload is validated before sending, and discard restores the saved values', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.getByLabel('Default maximum workload').fill('0');
    await page.getByRole('button', { name: 'Save settings' }).click();
    await expect(page.getByText('Enter a whole number from 1 to 100.')).toBeVisible();
    await expect(dialog(page)).toHaveCount(0);
    expect(api.admin.count('PUT', '/api/admin/assignment-settings')).toBe(0);
    await page.getByRole('button', { name: 'Discard changes' }).click();
    await expect(page.getByLabel('Default maximum workload')).toHaveValue('5');
  });

  test('a stale version keeps my edits and retries on the latest; the form is never silently reset', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.getByLabel('Default maximum workload').fill('9');
    api.admin.assignment.version = 3;
    await page.getByRole('button', { name: 'Save settings' }).click();
    await dialog(page).getByRole('button', { name: 'Save settings' }).click();
    await expect(page.getByTestId('form-error')).toContainText('changed by someone else');
    await expect(page.getByLabel('Default maximum workload')).toHaveValue('9');
    await page.getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await page.getByRole('button', { name: 'Save settings' }).click();
    await dialog(page).getByRole('button', { name: 'Save settings' }).click();
    await toast(page, 'Assignment settings were saved.');
    expect((api.admin.last('PUT', '/api/admin/assignment-settings')!.body as { version: number }).version).toBe(3);
  });

  test('ABSENT: the page says the catalog has not been deployed, is read-only, and sends nothing', async ({ page, api }) => {
    api.admin.strategies = null;
    await openAdmin(page, api, PATH);
    const note = page.getByTestId('catalog-unavailable');
    await expect(note).toHaveAttribute('data-catalog-status', 'ABSENT');
    await expect(note).toContainText('has not been deployed yet');
    await expect(note).not.toContainText('could not be reached');
    await expect(page.getByRole('combobox', { name: 'Active strategy' })).toBeDisabled();
    await expect(page.getByLabel('Default maximum workload')).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Add override' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Save settings' })).toBeDisabled();
    await expect(note.getByRole('button', { name: 'Try again' })).toHaveCount(0);
    expect(api.admin.count('PUT', '/api/admin/assignment-settings')).toBe(0);
  });

  test('FAILED: a temporary problem, never described as "not deployed", read-only, and retry recovers', async ({ page, api }) => {
    api.admin.catalogFailed = true;
    await openAdmin(page, api, PATH);
    const note = page.getByTestId('catalog-unavailable');
    await expect(note).toHaveAttribute('data-catalog-status', 'FAILED');
    await expect(note).toContainText('could not be reached');
    await expect(note).toContainText('Try again');
    await expect(note).not.toContainText(/deployed/i);
    await expect(page.getByRole('combobox', { name: 'Active strategy' })).toBeDisabled();
    await expect(page.getByLabel('Default maximum workload')).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Save settings' })).toBeDisabled();
    expect(api.admin.count('PUT', '/api/admin/assignment-settings')).toBe(0);
    api.admin.catalogFailed = false;
    await note.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByTestId('catalog-unavailable')).toHaveCount(0);
    await expect(page.getByLabel('Default maximum workload')).toBeEnabled();
    await expect(page.getByRole('combobox', { name: 'Active strategy' })).toBeEnabled();
  });

  test('AVAILABLE but empty: the page says nothing is registered, is read-only, and offers no strategies', async ({ page, api }) => {
    api.admin.strategies = [];
    await openAdmin(page, api, PATH);
    const note = page.getByTestId('catalog-unavailable');
    await expect(note).toHaveAttribute('data-catalog-status', 'AVAILABLE');
    await expect(note).toHaveAttribute('data-catalog-notice', 'EMPTY');
    await expect(note).toContainText('No assignment strategies are currently registered');
    await expect(note).not.toContainText(/deployed|could not be reached/i);
    await expect(page.getByRole('combobox', { name: 'Active strategy' })).toBeDisabled();
    await expect(page.getByLabel('Default maximum workload')).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Add override' })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Save settings' })).toBeDisabled();
    // the saved strategy stays visible, labelled as not registered, and is the only entry
    await expect(page.getByRole('combobox', { name: 'Active strategy' })).toContainText('ROUND_ROBIN (not registered)');
    expect(api.admin.count('PUT', '/api/admin/assignment-settings')).toBe(0);
  });

  test('AVAILABLE with strategies: normal editable behaviour and no notice', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await expect(page.getByTestId('catalog-unavailable')).toHaveCount(0);
    await expect(page.getByRole('combobox', { name: 'Active strategy' })).toBeEnabled();
    await expect(page.getByLabel('Default maximum workload')).toBeEnabled();
    await expect(page.getByRole('combobox', { name: 'Active strategy' })).not.toContainText('not registered');
  });

  test('a saved but unregistered strategy stays visible, is not selectable, and must be replaced before saving', async ({ page, api }) => {
    api.admin.strategies = ['SKILL_BASED']; // ROUND_ROBIN is saved but no longer registered
    api.admin.assignment.categoryOverrides = [{ categoryId: 1, strategy: 'ROUND_ROBIN' }, { categoryId: 2, strategy: 'SKILL_BASED' }];
    await openAdmin(page, api, PATH);
    const active = page.getByRole('combobox', { name: 'Active strategy' });
    await expect(active).toContainText('ROUND_ROBIN (not registered)');
    await expect(page.getByText('no longer registered').first()).toBeVisible();
    await active.click();
    await expect(page.getByRole('option', { name: 'SKILL_BASED', exact: true })).toBeEnabled();
    await expect(page.getByRole('option', { name: 'ROUND_ROBIN (not registered)' })).toBeDisabled();
    expect(await page.getByRole('option').count()).toBe(2); // the registered key and the disabled saved one; nothing else is offered
    await page.keyboard.press('Escape');
    // the override with the stale key is shown too, and is not a choice in any dropdown
    const row = page.getByTestId('override-row').filter({ hasText: 'Network' });
    await expect(row.getByRole('combobox')).toContainText('ROUND_ROBIN (not registered)');
    // nothing is replaced silently: saving with the stale key is refused on the field, and no request is sent
    await page.getByLabel('Default maximum workload').fill('9');
    await page.getByRole('button', { name: 'Save settings' }).click();
    await expect(page.getByText('“ROUND_ROBIN” is not registered. Choose a registered strategy.').first()).toBeVisible();
    await expect(dialog(page)).toHaveCount(0);
    expect(api.admin.count('PUT', '/api/admin/assignment-settings')).toBe(0);
    await pick(page, 'Active strategy', 'SKILL_BASED');
    await pick(page, 'Strategy for Network', 'SKILL_BASED');
    await page.getByRole('button', { name: 'Save settings' }).click();
    await dialog(page).getByRole('button', { name: 'Save settings' }).click();
    await toast(page, 'Assignment settings were saved.');
    const body = api.admin.last('PUT', '/api/admin/assignment-settings')!.body as { activeStrategy: string; categoryOverrides: { strategy: string }[] };
    expect(body.activeStrategy).toBe('SKILL_BASED');
    expect(body.categoryOverrides.every((o) => o.strategy === 'SKILL_BASED')).toBe(true);
  });

  test('a backend that predates strategyCatalogStatus is still read correctly from the list alone', async ({ page, api }) => {
    api.admin.reportsCatalogStatus = false;
    await openAdmin(page, api, PATH);
    await expect(page.getByTestId('catalog-unavailable')).toHaveCount(0);
    await expect(page.getByRole('combobox', { name: 'Active strategy' })).toBeEnabled();
    api.admin.strategies = null;
    await page.reload();
    await expect(page.getByTestId('catalog-unavailable')).toHaveAttribute('data-catalog-status', 'ABSENT');
  });

  test('an inconsistent catalog answer is refused instead of guessed at', async ({ page, api }) => {
    await page.route('**/api/admin/assignment-settings', (route) => route.fulfill({
      status: 200, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' },
      body: JSON.stringify({
        activeStrategy: 'ROUND_ROBIN', defaultMaxWorkload: 5, categoryOverrides: [], version: 0, updatedAt: '2026-11-10T00:00:00Z', updatedByUserId: null,
        strategyCatalogStatus: 'AVAILABLE', availableStrategies: null, // claims AVAILABLE but carries no list
      }),
    }));
    await openAdmin(page, api, PATH, newAdmin('inconsistent'));
    await expect(page.getByRole('alert').filter({ hasText: 'We couldn’t load this.' })).toBeVisible();
    await expect(page.getByRole('combobox', { name: 'Active strategy' })).toHaveCount(0);
  });

  test('a 503 from the server on save (catalog went away) shows the server’s explanation and keeps the form', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.getByLabel('Default maximum workload').fill('8');
    api.admin.strategies = null;
    await page.getByRole('button', { name: 'Save settings' }).click();
    await dialog(page).getByRole('button', { name: 'Save settings' }).click();
    await expect(page.getByTestId('form-error')).toContainText('strategy catalog is not available');
    await expect(page.getByLabel('Default maximum workload')).toHaveValue('8');
  });

  test('a registered-strategy rejection from the server is shown on the field', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.getByLabel('Default maximum workload').fill('8');
    api.admin.strategies = ['SKILL_BASED']; // ROUND_ROBIN was un-registered meanwhile
    await page.getByRole('button', { name: 'Save settings' }).click();
    await dialog(page).getByRole('button', { name: 'Save settings' }).click();
    await expect(page.getByText("'ROUND_ROBIN' is not a registered strategy")).toBeVisible();
  });

  test('error state offers retry', async ({ page, api }) => {
    api.admin.failures.push({ match: 'GET /api/admin/assignment-settings', status: 500, code: 'INTERNAL_ERROR', detail: 'x', times: 1 });
    await openAdmin(page, api, PATH);
    await expect(page.getByRole('alert').filter({ hasText: 'We couldn’t load this.' })).toBeVisible();
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(page.getByLabel('Default maximum workload')).toBeVisible();
  });
});
