import { test, expect, newAdmin } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, settle, signIn, trackErrors } from './helpers';
import { dialog, openAdmin, pick, registerSelf, rowMenu, rows, search, summary, toast } from './admin-helpers';

test.describe('admin: access and shell', () => {
  test('unauthenticated /admin redirects to sign-in after the one session check, with no other API call', async ({ page, api }) => {
    await page.goto('/admin/users');
    await page.waitForURL(/\/\?redirect=%2Fadmin%2Fusers/);
    expect(api.calls).toEqual(['/api/users/me']);
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
  test('shows real figures, attention items that deep-link into filtered lists, and the persisted recent changes', async ({ page, api }) => {
    await openAdmin(page, api);
    const figures = page.getByRole('region', { name: 'Key figures' });
    await expect(figures).toContainText('Total users');
    await expect(figures.getByText('Total users').locator('xpath=..')).toContainText('7');
    await expect(figures.getByText('Total users').locator('xpath=..')).toContainText('6 active');
    await expect(figures).toContainText('Technician profiles');
    await expect(page.getByTestId('recent-changes').getByRole('listitem')).toHaveCount(6);
    // figures are the totals of ordinary list calls (page size 1), never a stored or simulated number
    expect(api.admin.requests.every((r) => r.method === 'GET')).toBe(true);
    await page.getByRole('link', { name: /1 inactive account/ }).click();
    await page.waitForURL('**/admin/users?active=false');
    await expect(rows(page)).toHaveCount(1);
    await expect(rows(page).first()).toContainText('Barbara Liskov');
  });

  test('flags a missing global SLA policy', async ({ page, api }) => {
    api.admin.sla = api.admin.sla.filter((p) => !(p.serviceId === null && p.priority === 'P2'));
    await openAdmin(page, api);
    await expect(page.getByRole('link', { name: /No global SLA policy for P2/ })).toBeVisible();
  });

  test('tells the three assignment-catalog problems apart, and says nothing when all is well', async ({ page, api }) => {
    await openAdmin(page, api);
    await expect(page.getByRole('link', { name: /catalog|strateg/i })).toHaveCount(0);

    api.admin.strategies = null;
    await page.reload();
    await expect(page.getByRole('link', { name: /Assignment strategy catalog not deployed yet/ })).toBeVisible();

    api.admin.strategies = ['ROUND_ROBIN', 'SKILL_BASED'];
    api.admin.catalogFailed = true;
    await page.reload();
    await expect(page.getByRole('link', { name: /Assignment strategy catalog could not be reached/ })).toBeVisible();
    await expect(page.getByRole('link', { name: /not deployed/ })).toHaveCount(0);

    api.admin.catalogFailed = false;
    api.admin.strategies = [];
    await page.reload();
    await expect(page.getByRole('link', { name: /No assignment strategies are registered/ })).toBeVisible();
    await expect(page.getByRole('link', { name: /not deployed|could not be reached/ })).toHaveCount(0);

    api.admin.strategies = ['SKILL_BASED'];
    await page.reload();
    await expect(page.getByRole('link', { name: /Saved assignment strategy not registered: ROUND_ROBIN/ })).toBeVisible();
  });

  test('error state offers retry and recovers', async ({ page, api }) => {
    api.admin.failures.push({ match: 'GET /api/admin/users', status: 500, code: 'INTERNAL_ERROR', detail: 'boom', times: 1 });
    await signIn(page, api, newAdmin('overview-err'));
    await page.goto('/admin');
    await expect(page.getByRole('alert').filter({ hasText: 'We couldn’t load this.' }).first()).toBeVisible();
    await expect(page.getByText('boom')).toHaveCount(0); // raw server text is never shown for 5xx
    await page.getByRole('button', { name: 'Try again' }).first().click();
    await expect(page.getByRole('region', { name: 'Key figures' })).toBeVisible();
  });
});

test.describe('admin: users', () => {
  test('lists users from the API with canonical roles, status and no secrets', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await expect(rows(page)).toHaveCount(7);
    await expect(summary(page)).toHaveText('Showing 1–7 of 7 users');
    await expect(rows(page).filter({ hasText: 'Ada Lovelace' })).toContainText('Administrator');
    await expect(rows(page).filter({ hasText: 'Linus Torvalds' })).toContainText('Technician');
    await expect(rows(page).filter({ hasText: 'Barbara Liskov' })).toContainText('Inactive');
    const text = await page.locator('main').innerText();
    expect(text).not.toMatch(/password|hash|token/i);
    const first = api.admin.last('GET', '/api/admin/users')!;
    expect(first.query.get('page')).toBe('0');
    expect(first.query.get('size')).toBe('10');
    expect(first.query.get('sort')).toBe('name');
  });

  test('search, role, status and sort go to the API as query parameters; the URL stays shareable', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await search(page, 'turing');
    await expect(rows(page)).toHaveCount(1);
    expect(api.admin.last('GET', '/api/admin/users')!.query.get('search')).toBe('turing');
    await page.waitForURL('**/admin/users?q=turing');
    await page.getByRole('button', { name: 'Clear filters' }).click();
    await pick(page, 'Filter by role', 'Administrator');
    await expect(rows(page)).toHaveCount(2);
    expect(api.admin.last('GET', '/api/admin/users')!.query.get('role')).toBe('ADMIN');
    await pick(page, 'Filter by status', 'Inactive');
    await expect(page.getByText('No users match your filters')).toBeVisible();
    expect(api.admin.last('GET', '/api/admin/users')!.query.get('active')).toBe('false');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await page.getByRole('button', { name: 'Role', exact: true }).click();
    await expect(page.getByRole('columnheader', { name: 'Role' })).toHaveAttribute('aria-sort', 'ascending');
    expect(api.admin.last('GET', '/api/admin/users')!.query.get('sort')).toBe('role');
    await page.getByRole('button', { name: 'Role', exact: true }).click();
    expect(api.admin.last('GET', '/api/admin/users')!.query.get('dir')).toBe('desc');
  });

  test('pages through the API 10 at a time', async ({ page, api }) => {
    for (let i = 0; i < 8; i++) api.admin.addUser(`Extra Person ${i}`, 'EMPLOYEE');
    await openAdmin(page, api, '/admin/users');
    await expect(summary(page)).toHaveText('Showing 1–10 of 15 users');
    await page.getByRole('button', { name: 'Next' }).click();
    await expect(summary(page)).toHaveText('Showing 11–15 of 15 users');
    expect(api.admin.last('GET', '/api/admin/users')!.query.get('page')).toBe('1');
  });

  test('adding a user validates first, sends only the contract fields, and never keeps the password', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await page.getByRole('button', { name: 'Add user' }).first().click();
    await dialog(page).getByRole('button', { name: 'Add user' }).click();
    await expect(dialog(page).getByText('Enter a name.')).toBeVisible();
    await expect(dialog(page).getByText('Enter an email address.')).toBeVisible();
    await expect(dialog(page).getByText('Use at least 8 characters.')).toBeVisible();
    expect(api.admin.count('POST', '/api/admin/users')).toBe(0);
    await dialog(page).getByLabel('Full name').fill('  Dana Scully  ');
    await dialog(page).getByLabel('Email address').fill('Dana@Example.test');
    await dialog(page).getByLabel('Initial password').fill('s3cret-pass');
    await dialog(page).getByRole('button', { name: 'Add user' }).click();
    await toast(page, 'Dana Scully was added.');
    expect(api.admin.last('POST', '/api/admin/users')!.body).toEqual({ email: 'Dana@Example.test', name: 'Dana Scully', password: 's3cret-pass', role: 'EMPLOYEE' });
    await expect(rows(page).filter({ hasText: 'Dana Scully' })).toContainText('Employee');
    await page.getByRole('button', { name: 'Add user' }).first().click();
    await expect(dialog(page).getByLabel('Initial password')).toHaveValue('');
  });

  test('a duplicate email is explained, and what was typed is kept', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await page.getByRole('button', { name: 'Add user' }).first().click();
    await dialog(page).getByLabel('Full name').fill('Another Ada');
    await dialog(page).getByLabel('Email address').fill('ada.lovelace@example.test');
    await dialog(page).getByLabel('Initial password').fill('long-enough-1');
    await dialog(page).getByRole('button', { name: 'Add user' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText('Email is already registered');
    await expect(dialog(page).getByLabel('Full name')).toHaveValue('Another Ada');
    await expect(dialog(page).getByLabel('Initial password')).toHaveValue('long-enough-1');
  });

  test('editing sends the version; a stale version offers "keep my edits" and then succeeds', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await rowMenu(page, 'Edsger Dijkstra');
    await page.getByRole('menuitem', { name: 'Edit details' }).click();
    api.admin.bump('users', 6); // someone else saves first
    await dialog(page).getByLabel('Full name').fill('Edsger W. Dijkstra');
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText('changed by someone else');
    expect((api.admin.last('PUT', '/api/admin/users/6')!.body as { version: number }).version).toBe(0);
    await expect(dialog(page).getByLabel('Full name')).toHaveValue('Edsger W. Dijkstra');
    await dialog(page).getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await expect(dialog(page).getByTestId('form-error')).toHaveCount(0);
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, 'Edsger W. Dijkstra was updated.');
    expect((api.admin.last('PUT', '/api/admin/users/6')!.body as { version: number }).version).toBe(1);
  });

  test('"discard mine and load latest" replaces the form with the stored values', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await rowMenu(page, 'Edsger Dijkstra');
    await page.getByRole('menuitem', { name: 'Edit details' }).click();
    api.admin.users.find((u) => u.id === 6)!.name = 'Renamed Elsewhere';
    api.admin.bump('users', 6);
    await dialog(page).getByLabel('Full name').fill('Mine');
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await dialog(page).getByRole('button', { name: 'Discard mine and load latest' }).click();
    await expect(dialog(page).getByLabel('Full name')).toHaveValue('Renamed Elsewhere');
  });

  test('changing a role asks the API; changing your own role is refused with the server’s message', async ({ page, api }) => {
    const me = newAdmin('self');
    registerSelf(api, me);
    await openAdmin(page, api, '/admin/users', me);
    await rowMenu(page, 'Edsger Dijkstra');
    await page.getByRole('menuitem', { name: 'Change role' }).click();
    await pick(page, 'Role', 'Technician');
    await dialog(page).getByRole('button', { name: 'Change role' }).click();
    await toast(page, 'Edsger Dijkstra is now Technician.');
    expect(api.admin.last('PUT', '/api/admin/users/6/role')!.body).toEqual({ role: 'TECHNICIAN', version: 0 });
    await rowMenu(page, me.name);
    await page.getByRole('menuitem', { name: 'Change role' }).click();
    await pick(page, 'Role', 'Employee');
    await dialog(page).getByRole('button', { name: 'Change role' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText('You cannot change your own administrator role');
  });

  test('a role cannot change while the user has an active technician profile, and the server says why', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await rowMenu(page, 'Linus Torvalds');
    await page.getByRole('menuitem', { name: 'Change role' }).click();
    await pick(page, 'Role', 'Employee');
    await dialog(page).getByRole('button', { name: 'Change role' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText('deactivate the profile before changing the role');
    await expect(rows(page).filter({ hasText: 'Linus Torvalds' })).toContainText('Technician');
  });

  test('deactivating is confirmed, protects the last administrator and yourself, and activating needs no scare text', async ({ page, api }) => {
    const me = newAdmin('self2');
    registerSelf(api, me);
    await openAdmin(page, api, '/admin/users', me);
    await rowMenu(page, 'Linus Torvalds');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await expect(dialog(page)).toContainText('Their history is kept');
    await dialog(page).getByRole('button', { name: 'Deactivate user' }).click();
    await toast(page, 'Linus Torvalds was deactivated.');
    expect(api.admin.last('POST', '/api/admin/users/3/deactivate')!.body).toEqual({ version: 0 });
    await rowMenu(page, me.name);
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate user' }).click();
    await expect(dialog(page)).toContainText('You cannot deactivate your own account');
    await expect(dialog(page)).toBeVisible();
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await rowMenu(page, 'Barbara Liskov');
    await page.getByRole('menuitem', { name: 'Activate' }).click();
    await dialog(page).getByRole('button', { name: 'Activate user' }).click();
    await toast(page, 'Barbara Liskov was activated.');
  });

  test('the last active administrator cannot be deactivated', async ({ page, api }) => {
    api.admin.users.find((u) => u.id === 2)!.active = false;
    await openAdmin(page, api, '/admin/users');
    await rowMenu(page, 'Ada Lovelace');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate user' }).click();
    await expect(dialog(page)).toContainText('last active administrator');
  });

  test('a stale deactivate refreshes the version so confirming again works', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await rowMenu(page, 'Alan Turing');
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    api.admin.bump('users', 5);
    await dialog(page).getByRole('button', { name: 'Deactivate user' }).click();
    await expect(dialog(page)).toContainText('changed by someone else');
    await dialog(page).getByRole('button', { name: 'Deactivate user' }).click();
    await toast(page, 'Alan Turing was deactivated.');
    expect(api.admin.last('POST', '/api/admin/users/5/deactivate')!.body).toEqual({ version: 1 });
  });

  test('resetting a password validates it, sends only the password and says it is never shown again', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await rowMenu(page, 'Margaret Hamilton');
    await page.getByRole('menuitem', { name: 'Reset password' }).click();
    await expect(dialog(page)).toContainText('never shown again');
    await dialog(page).getByRole('button', { name: 'Reset password' }).click();
    await expect(dialog(page).getByText('Use at least 8 characters.')).toBeVisible();
    expect(api.admin.count('POST', '/api/admin/users/4/reset-password')).toBe(0);
    await dialog(page).getByLabel('New password').fill('brand-new-pass');
    await dialog(page).getByRole('button', { name: 'Reset password' }).click();
    await toast(page, /Password reset for Margaret Hamilton/);
    expect(api.admin.last('POST', '/api/admin/users/4/reset-password')!.body).toEqual({ password: 'brand-new-pass' });
  });

  test('revoking sessions is confirmed and sends no body fields', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await rowMenu(page, 'Alan Turing');
    await page.getByRole('menuitem', { name: 'Revoke sessions' }).click();
    await dialog(page).getByRole('button', { name: 'Revoke sessions' }).click();
    await toast(page, 'Sessions of Alan Turing were revoked.');
    expect(api.admin.count('POST', '/api/admin/users/5/revoke-sessions')).toBe(1);
  });

  test('rows with the same display name have distinct accessible action names (the email tells them apart)', async ({ page, api }) => {
    api.admin.addUser('Sam Twin', 'EMPLOYEE', 'sam.one@example.test');
    api.admin.addUser('Sam Twin', 'EMPLOYEE', 'sam.two@example.test');
    await openAdmin(page, api, '/admin/users');
    const names = await page.getByRole('button', { name: /^Actions for Sam Twin/ }).evaluateAll((els) => els.map((e) => e.getAttribute('aria-label')));
    expect(names).toEqual(['Actions for Sam Twin (sam.one@example.test)', 'Actions for Sam Twin (sam.two@example.test)']);
    expect(new Set(names).size).toBe(2);
    await page.getByRole('button', { name: 'Actions for Sam Twin (sam.two@example.test)' }).click();
    await page.getByRole('menuitem', { name: 'Edit details' }).click();
    await expect(dialog(page).getByLabel('Email address')).toHaveValue('sam.two@example.test');
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
  });

  test('there is no delete action, and the page offers no bulk or department features that the API lacks', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await rowMenu(page, 'Alan Turing');
    await expect(page.getByRole('menuitem', { name: /delete/i })).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(page.getByRole('combobox', { name: /department/i })).toHaveCount(0);
    await expect(page.getByRole('checkbox')).toHaveCount(0);
    expect(api.admin.requests.filter((r) => r.method === 'DELETE')).toEqual([]);
  });

  test('loading, error and empty states', async ({ page, api }) => {
    api.admin.failures.push({ match: 'GET /api/admin/users', status: 500, code: 'INTERNAL_ERROR', detail: 'db exploded', times: 1 });
    await signIn(page, api, newAdmin('states'));
    await page.goto('/admin/users');
    await expect(page.getByRole('alert').filter({ hasText: 'We couldn’t load this.' }).first()).toBeVisible();
    await expect(page.getByText('db exploded')).toHaveCount(0);
    await page.getByRole('button', { name: 'Try again' }).click();
    await expect(rows(page).first()).toBeVisible();
    api.admin.users = [];
    await page.reload();
    await expect(page.getByText('No users yet')).toBeVisible();
  });

  test('a 403 from the server replaces the data with a permission message and no retry loop', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await expect(rows(page).first()).toBeVisible();
    api.admin.failures.push({ match: 'GET /api/admin/users', status: 403, code: 'FORBIDDEN', detail: 'internal detail', times: 1 });
    await search(page, 'ada');
    await expect(page.getByRole('alert').filter({ hasText: 'You don’t have permission to do this.' }).first()).toBeVisible();
    await expect(page.getByText('internal detail')).toHaveCount(0);
  });

  test('a loading skeleton shows while the list is being fetched', async ({ page, api }) => {
    await signIn(page, api, newAdmin('loading'));
    await page.route('**/api/admin/users?*', async (route) => { await new Promise((r) => setTimeout(r, 600)); await route.fallback(); });
    await page.goto('/admin/users');
    await expect(page.getByRole('status', { name: 'Loading users' })).toBeVisible();
    await expect(rows(page).first()).toBeVisible();
  });

  test('an expired session (401) mid-use clears the token and returns to sign-in', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    api.admin.failures.push({ match: 'GET /api/admin/users', status: 401, code: 'UNAUTHENTICATED', detail: 'expired', times: 1 });
    await search(page, 'ada');
    await page.waitForURL(/\/\?redirect=/);
    expect(await page.evaluate(() => localStorage.getItem('it_sm_token'))).toBeNull();
  });
});


test.describe('admin: quality', () => {
  const pages: [string, string][] = [
    ['/admin', 'Key figures'], ['/admin/users', ''], ['/admin/technicians', ''], ['/admin/categories', ''], ['/admin/services', ''], ['/admin/sla-policies', ''], ['/admin/assignment-settings', 'Current configuration'],
  ];
  for (const [path, region] of pages) {
    test(`${path} has no axe violations and no console errors`, async ({ page, api }) => {
      await signIn(page, api, newAdmin('axe'));
      const errors = trackErrors(page);
      await page.goto(path);
      if (region === 'Key figures') await expect(page.getByRole('region', { name: 'Key figures' })).toBeVisible();
      else if (region) await expect(page.getByText(region).first()).toBeVisible();
      else await expect(rows(page).first()).toBeVisible();
      await settle(page);
      expect(await axeViolations(page)).toEqual([]);
      expect(errors).toEqual([]);
    });
  }

  test('the add-user dialog passes axe', async ({ page, api }) => {
    await openAdmin(page, api, '/admin/users');
    await page.getByRole('button', { name: 'Add user' }).first().click();
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
  });
});

test.describe('admin: responsive', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  for (const path of ['/admin', '/admin/users', '/admin/technicians', '/admin/categories', '/admin/services', '/admin/sla-policies', '/admin/assignment-settings']) {
    test(`${path} has no horizontal page overflow`, async ({ page, api }) => {
      await signIn(page, api, newAdmin('mobile'));
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await page.waitForLoadState('networkidle');
      await expectNoHorizontalOverflow(page);
    });
  }

  test('the nav opens as a drawer', async ({ page, api }) => {
    await signIn(page, api, newAdmin('drawer'));
    await page.goto('/admin');
    await page.getByRole('button', { name: 'Toggle navigation' }).click();
    const nav = page.getByRole('dialog').getByRole('navigation', { name: 'Admin sections' });
    await expect(nav).toBeVisible();
    await nav.getByRole('link', { name: 'Technicians' }).click();
    await page.waitForURL('**/admin/technicians');
    await expect(page.getByRole('dialog')).toBeHidden();
  });
});
