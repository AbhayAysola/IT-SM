import { test, expect, newAdmin } from './fixtures';
import { dialog, openAdmin, pick, rowFor, rowMenu, rows, search, summary, toast } from './admin-helpers';

const PATH = '/admin/technicians';

test.describe('admin: technicians', () => {
  test('lists profiles from the API; availability and max workload are explained as eligibility and a ceiling', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await expect(rows(page)).toHaveCount(2);
    await expect(summary(page)).toHaveText('Showing 1–2 of 2 technicians');
    await expect(page.getByText(/eligibility flag/)).toBeVisible();
    await expect(page.getByText(/configured ceiling/)).toBeVisible();
    const row = rowFor(page, 'Linus Torvalds');
    await expect(row).toContainText('Available');
    await expect(row).toContainText('Network · 4');
    await expect(row.getByLabel('Team lead')).toBeVisible();
    // no fields from the old mock: team, shift, workload now, skill level
    const text = await page.locator('main table').innerText();
    expect(text).not.toMatch(/shift|current workload|on leave|busy|team:/i);
    const q = api.admin.last('GET', '/api/admin/technicians')!.query;
    expect([q.get('page'), q.get('size'), q.get('sort')]).toEqual(['0', '10', 'name']);
  });

  test('filters and search go to the API as the supported query parameters', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await search(page, 'hamilton');
    await expect(rowFor(page, 'Margaret Hamilton')).toBeVisible();
    await expect(rows(page)).toHaveCount(1);
    await expect.poll(() => api.admin.last('GET', '/api/admin/technicians')?.query.get('search')).toBe('hamilton');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await pick(page, 'Filter by team lead', 'Team leads');
    // Each filter here leaves a single row, so wait for the row this filter returns rather than the
    // row count alone, which the previous filter's stale row would also satisfy.
    await expect(rowFor(page, 'Linus Torvalds')).toBeVisible();
    await expect(rows(page)).toHaveCount(1);
    await expect.poll(() => api.admin.last('GET', '/api/admin/technicians')?.query.get('teamLead')).toBe('true');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await pick(page, 'Filter by skill', 'Hardware');
    await expect(rowFor(page, 'Margaret Hamilton')).toBeVisible();
    await expect(rows(page)).toHaveCount(1);
    await expect.poll(() => api.admin.last('GET', '/api/admin/technicians')?.query.get('categoryId')).toBe('2');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await pick(page, 'Filter by availability', 'Not available');
    await expect(page.getByText('No technicians match your filters')).toBeVisible();
    await expect.poll(() => api.admin.last('GET', '/api/admin/technicians')?.query.get('available')).toBe('false');
    await page.getByRole('button', { name: 'Clear filters' }).first().click();
    await page.getByRole('button', { name: 'Max workload' }).click();
    await expect.poll(() => api.admin.last('GET', '/api/admin/technicians')?.query.get('sort')).toBe('maxWorkload');
  });

  test('a profile is created for an existing TECHNICIAN user with skills, validated before sending', async ({ page, api }) => {
    const spare = api.admin.addUser('Ken Thompson', 'TECHNICIAN');
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Add profile' }).first().click();
    await dialog(page).getByRole('button', { name: 'Create profile' }).click();
    await expect(dialog(page).getByText('Choose a technician user.')).toBeVisible();
    expect(api.admin.count('POST', '/api/admin/technicians')).toBe(0);
    await pick(page, 'Technician user', /Ken Thompson/);
    await dialog(page).getByLabel('Maximum workload').fill('0');
    await dialog(page).getByRole('button', { name: 'Create profile' }).click();
    await expect(dialog(page).getByText('Enter a whole number from 1 to 100.')).toBeVisible();
    await dialog(page).getByLabel('Maximum workload').fill('6');
    await dialog(page).getByLabel('Years of experience').fill('3');
    await dialog(page).getByRole('button', { name: 'Add skill' }).click();
    await pick(page, 'Skill 1 category', 'Network');
    await pick(page, 'Skill 1 proficiency', 'Level 5');
    await dialog(page).getByRole('button', { name: 'Create profile' }).click();
    await toast(page, 'Profile created for Ken Thompson.');
    expect(api.admin.last('POST', '/api/admin/technicians')!.body).toEqual({
      userId: spare.id, maxWorkload: 6, experienceYears: 3, available: true, isTeamLead: false, reportsToUserId: null,
      skills: [{ categoryId: 1, proficiency: 5 }],
    });
    await expect(rowFor(page, 'Ken Thompson')).toContainText('Network · 5');
  });

  test('the picker searches with the supported `search` parameter on both lists', async ({ page, api }) => {
    api.admin.addUser('Ken Thompson', 'TECHNICIAN');
    api.admin.addUser('Dennis Ritchie', 'TECHNICIAN');
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Add profile' }).first().click();
    await page.getByRole('combobox', { name: 'Technician user' }).click();
    await expect(page.getByRole('option')).toHaveCount(5); // Linus, Margaret, Alan (seed) + Ken, Dennis
    await page.keyboard.press('Escape');
    await dialog(page).getByLabel('Find a technician user').fill('ritchie');
    await expect.poll(() => api.admin.requests.some((r) => r.path === '/api/admin/users' && r.query.get('search') === 'ritchie' && r.query.get('role') === 'TECHNICIAN' && r.query.get('active') === 'true')).toBe(true);
    // the same term goes to the technicians list, which is how existing profiles are identified
    await expect.poll(() => api.admin.requests.some((r) => r.path === '/api/admin/technicians' && r.query.get('search') === 'ritchie' && r.query.get('size') === '100')).toBe(true);
    await page.getByRole('combobox', { name: 'Technician user' }).click();
    await expect(page.getByRole('option')).toHaveCount(1);
    await expect(page.getByRole('option').first()).toContainText('Dennis Ritchie');
    await page.keyboard.press('Escape');
    await dialog(page).getByLabel('Find a technician user').fill('nobody-by-this-name');
    await page.getByRole('combobox', { name: 'Technician user' }).click();
    await expect(page.getByTestId('no-candidates')).toBeVisible();
  });

  test('users who already have a profile are listed, labelled and cannot be chosen', async ({ page, api }) => {
    api.admin.addUser('Ken Thompson', 'TECHNICIAN');
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Add profile' }).first().click();
    await page.getByRole('combobox', { name: 'Technician user' }).click();
    for (const name of ['Linus Torvalds', 'Margaret Hamilton']) {
      const option = page.getByRole('option', { name: new RegExp(name) });
      await expect(option).toContainText('(already has a profile)');
      await expect(option).toBeDisabled();
    }
    const eligible = page.getByRole('option', { name: /Ken Thompson/ });
    await expect(eligible).not.toContainText('already has a profile');
    await expect(eligible).toBeEnabled();
    // clicking a disabled option changes nothing
    await page.getByRole('option', { name: /Linus Torvalds/ }).click({ force: true });
    await expect(page.getByRole('option', { name: /Ken Thompson/ })).toBeVisible();
    await page.keyboard.press('Escape');
    await dialog(page).getByRole('button', { name: 'Create profile' }).click();
    await expect(dialog(page).getByText('Choose a technician user.')).toBeVisible();
    expect(api.admin.count('POST', '/api/admin/technicians')).toBe(0);
  });

  test('a duplicate the lists did not show is still refused by the server, with a recoverable form', async ({ page, api }) => {
    const late = api.admin.addUser('Ken Thompson', 'TECHNICIAN');
    const spare = api.admin.addUser('Dennis Ritchie', 'TECHNICIAN');
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Add profile' }).first().click();
    await pick(page, 'Technician user', /Ken Thompson/);
    await dialog(page).getByLabel('Maximum workload').fill('8');
    api.admin.addTechnician(late.id, 5, 1, []); // another administrator creates it first
    await dialog(page).getByRole('button', { name: 'Create profile' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText(`User ${late.id} already has a technician profile`);
    await expect(dialog(page).getByLabel('Maximum workload')).toHaveValue('8'); // nothing typed was lost
    // recover: pick someone else and save without retyping
    await pick(page, 'Technician user', /Dennis Ritchie/);
    await dialog(page).getByRole('button', { name: 'Create profile' }).click();
    await toast(page, 'Profile created for Dennis Ritchie.');
    expect((api.admin.last('POST', '/api/admin/technicians')!.body as { userId: number; maxWorkload: number })).toMatchObject({ userId: spare.id, maxWorkload: 8 });
  });

  test('rows with the same display name have distinct accessible action names (the email tells them apart)', async ({ page, api }) => {
    const a = api.admin.addUser('Pat Twin', 'TECHNICIAN', 'pat.one@example.test');
    const b = api.admin.addUser('Pat Twin', 'TECHNICIAN', 'pat.two@example.test');
    api.admin.addTechnician(a.id, 4, 1, []); api.admin.addTechnician(b.id, 6, 2, []);
    await openAdmin(page, api, PATH);
    const names = await page.getByRole('button', { name: /^Actions for Pat Twin/ }).evaluateAll((els) => els.map((e) => e.getAttribute('aria-label')));
    expect(names).toEqual(['Actions for Pat Twin (pat.one@example.test)', 'Actions for Pat Twin (pat.two@example.test)']);
    await page.getByRole('button', { name: 'Actions for Pat Twin (pat.two@example.test)' }).click();
    await page.getByRole('menuitem', { name: 'Edit details' }).click();
    await expect(dialog(page).getByLabel('Maximum workload')).toHaveValue('6');
  });

  test('editing details sends the version; stale versions are handled explicitly', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await rowMenu(page, 'Margaret Hamilton');
    await page.getByRole('menuitem', { name: 'Edit details' }).click();
    api.admin.bump('technicians', 4);
    await dialog(page).getByLabel('Maximum workload').fill('9');
    await dialog(page).getByLabel('Available').uncheck();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(dialog(page).getByTestId('form-error')).toContainText('changed by someone else');
    await dialog(page).getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, 'Margaret Hamilton was updated.');
    expect(api.admin.last('PUT', '/api/admin/technicians/4')!.body).toEqual({
      maxWorkload: 9, experienceYears: 10, available: false, isTeamLead: false, reportsToUserId: null, version: 1,
    });
    await expect(rowFor(page, 'Margaret Hamilton')).toContainText('Not available');
  });

  test('skills are replaced as a whole set, including removing them all', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await rowMenu(page, 'Margaret Hamilton');
    await page.getByRole('menuitem', { name: 'Edit skills' }).click();
    await expect(dialog(page).getByTestId('skill-row')).toHaveCount(2);
    await dialog(page).getByRole('button', { name: 'Remove skill 2' }).click();
    await pick(page, 'Skill 1 proficiency', 'Level 3');
    await dialog(page).getByRole('button', { name: 'Save skills' }).click();
    await toast(page, 'Skills of Margaret Hamilton were saved.');
    expect(api.admin.last('PUT', '/api/admin/technicians/4/skills')!.body).toEqual({ skills: [{ categoryId: 2, proficiency: 3 }], version: 0 });
    await rowMenu(page, 'Margaret Hamilton');
    await page.getByRole('menuitem', { name: 'Edit skills' }).click();
    await dialog(page).getByRole('button', { name: 'Remove skill 1' }).click();
    await expect(dialog(page).getByTestId('no-skills')).toBeVisible();
    await dialog(page).getByRole('button', { name: 'Save skills' }).click();
    await toast(page, 'Skills of Margaret Hamilton were saved.');
    expect((api.admin.last('PUT', '/api/admin/technicians/4/skills')!.body as { skills: unknown[] }).skills).toEqual([]);
    await expect(rowFor(page, 'Margaret Hamilton')).toContainText('None');
  });

  test('a server-side skills error is shown on the row it belongs to', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await rowMenu(page, 'Linus Torvalds');
    await page.getByRole('menuitem', { name: 'Edit skills' }).click();
    api.admin.categories.find((c) => c.id === 1)!.id = 99; // the category vanished server-side
    api.admin.technicians.find((t) => t.userId === 3)!.skills = [];
    await dialog(page).getByRole('button', { name: 'Save skills' }).click();
    await expect(dialog(page).getByText('category 1 does not exist')).toBeVisible();
  });

  test('deactivation is confirmed and a refusal from the server stays visible in the dialog', async ({ page, api }) => {
    api.admin.technicians.find((t) => t.userId === 4)!.reportsToUserId = 3;
    await openAdmin(page, api, PATH);
    await rowMenu(page, 'Linus Torvalds');
    await page.getByRole('menuitem', { name: 'Deactivate profile' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate profile' }).click();
    await expect(dialog(page)).toContainText('Active technicians still report to this technician');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await rowMenu(page, 'Margaret Hamilton');
    await page.getByRole('menuitem', { name: 'Deactivate profile' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate profile' }).click();
    await toast(page, 'Profile of Margaret Hamilton was deactivated.');
    expect(api.admin.last('POST', '/api/admin/technicians/4/deactivate')!.body).toEqual({ version: 0 });
    await expect(rowFor(page, 'Margaret Hamilton')).toContainText('Inactive');
  });

  test('warns when the user behind a profile is no longer a technician', async ({ page, api }) => {
    api.admin.users.find((u) => u.id === 4)!.role = 'EMPLOYEE';
    await openAdmin(page, api, PATH);
    await expect(rowFor(page, 'Margaret Hamilton').getByTestId('account-warning')).toContainText('User role is now employee');
  });

  test('empty and failure states, and no delete action', async ({ page, api }) => {
    api.admin.failures.push({ match: 'GET /api/admin/technicians', status: 503, code: 'SERVICE_UNAVAILABLE', detail: 'x', times: 1 });
    await openAdmin(page, api, PATH, newAdmin('tech-err'));
    await expect(page.getByRole('alert').filter({ hasText: 'We couldn’t load this.' }).first()).toBeVisible();
    await page.getByRole('button', { name: 'Try again' }).first().click();
    await rowMenu(page, 'Margaret Hamilton');
    await expect(page.getByRole('menuitem', { name: /delete/i })).toHaveCount(0);
    await page.keyboard.press('Escape');
    api.admin.technicians = [];
    await page.reload();
    await expect(page.getByText('No technician profiles yet')).toBeVisible();
  });
});
