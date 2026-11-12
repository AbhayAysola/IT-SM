import { test, expect, type Page } from '@playwright/test';
import { Seed } from './support/api';
import { admin } from './support/env';
import { dialog, formError, openAdminAs, pick, rowMenu, rows, search, toast } from './support/ui';

/** Technician administration through the real UI and API. Each test creates its own technician user and category. */

let seed: Seed;
test.beforeAll(async () => { seed = await Seed.asAdmin(admin().email, admin().password); });
test.afterAll(async () => { await seed.dispose(); });

const find = async (page: Page, name: string) => { await search(page, name); await expect(rows(page).filter({ hasText: name })).toHaveCount(1); };
const menu = (page: Page, name: string) => rowMenu(page, new RegExp(`Actions for ${name}`));

test.describe('admin: technicians', () => {
  test('creating a profile through the form stores workload, experience and skills', async ({ page }) => {
    const u = await seed.user('tech', 'TECHNICIAN');
    const cat = await seed.category('SKL');
    await openAdminAs(page, admin(), '/admin/technicians');
    await page.getByRole('button', { name: 'Add profile' }).first().click();
    await dialog(page).getByLabel('Find a technician user').fill(u.email);
    await pick(page, 'Technician user', new RegExp(u.name));
    await dialog(page).getByLabel('Maximum workload').fill('6');
    await dialog(page).getByLabel('Years of experience').fill('3');
    await dialog(page).getByRole('button', { name: 'Add skill' }).click();
    await pick(page, 'Skill 1 category', new RegExp(cat.name));
    await pick(page, 'Skill 1 proficiency', 'Level 5');
    await dialog(page).getByRole('button', { name: 'Create profile' }).click();
    await toast(page, `Profile created for ${u.name}.`);
    const p = (await seed.admin.get(`/api/admin/technicians/${u.id}`)).body;
    expect(p.maxWorkload).toBe(6);
    expect(p.experienceYears).toBe(3);
    expect(p.skills.map((s: any) => [s.categoryId, s.proficiency])).toEqual([[cat.id, 5]]);
    await find(page, u.name);
  });

  test('a second profile for the same user, and a profile for a non-technician, are refused', async () => {
    const u = await seed.user('tech2', 'TECHNICIAN');
    await seed.technicianProfile(u.id);
    const dup = await seed.admin.post('/api/admin/technicians', { userId: u.id, maxWorkload: 5, experienceYears: 1, available: true, isTeamLead: false, skills: [] });
    expect(dup.status).toBe(409);
    expect(dup.body.detail ?? dup.body.message).toContain(`User ${u.id} already has a technician profile`);
    const emp = await seed.user('notech');
    const wrong = await seed.admin.post('/api/admin/technicians', { userId: emp.id, maxWorkload: 5, experienceYears: 1, available: true, isTeamLead: false, skills: [] });
    expect(wrong.status).toBeGreaterThanOrEqual(400);
    expect(wrong.status).toBeLessThan(500);
    expect((await seed.admin.get(`/api/admin/technicians/${emp.id}`)).status).toBe(404);
  });

  test('editing skills replaces the whole set', async ({ page }) => {
    const u = await seed.user('skills', 'TECHNICIAN');
    const cat = await seed.category('SK2');
    await seed.technicianProfile(u.id, { skills: [{ categoryId: cat.id, proficiency: 3 }] });
    await openAdminAs(page, admin(), '/admin/technicians');
    await find(page, u.name);
    await menu(page, u.name);
    await page.getByRole('menuitem', { name: 'Edit skills' }).click();
    await expect(dialog(page).getByTestId('skill-row')).toHaveCount(1);
    await dialog(page).getByRole('button', { name: 'Remove skill 1' }).click();
    await expect(dialog(page).getByTestId('no-skills')).toBeVisible();
    await dialog(page).getByRole('button', { name: 'Save skills' }).click();
    await toast(page, `Skills of ${u.name} were saved.`);
    expect((await seed.admin.get(`/api/admin/technicians/${u.id}`)).body.skills).toEqual([]);
  });

  test('optimistic lock: a concurrent edit makes the stale save fail, then "keep my edits" succeeds', async ({ page }) => {
    const u = await seed.user('lock', 'TECHNICIAN');
    const prof = await seed.technicianProfile(u.id);
    await openAdminAs(page, admin(), '/admin/technicians');
    await find(page, u.name);
    await menu(page, u.name);
    await page.getByRole('menuitem', { name: 'Edit details' }).click();
    const other = await seed.admin.put(`/api/admin/technicians/${u.id}`, { maxWorkload: 7, experienceYears: 1, available: true, isTeamLead: false, reportsToUserId: null, version: prof.version });
    expect(other.status).toBe(200);
    await dialog(page).getByLabel('Maximum workload').fill('9');
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(formError(page)).toContainText('changed by someone else');
    expect((await seed.admin.get(`/api/admin/technicians/${u.id}`)).body.maxWorkload, 'the stale save must not apply').toBe(7);
    await dialog(page).getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, `${u.name} was updated.`);
    expect((await seed.admin.get(`/api/admin/technicians/${u.id}`)).body.maxWorkload).toBe(9);
  });

  test('a role cannot change while the profile is active; after deactivating the profile it can', async ({ page }) => {
    const u = await seed.user('lockrole', 'TECHNICIAN');
    await seed.technicianProfile(u.id);
    await openAdminAs(page, admin(), '/admin/users');
    await search(page, u.email);
    await expect(rows(page).filter({ hasText: u.email })).toHaveCount(1);
    await rowMenu(page, new RegExp(`Actions for ${u.name}`));
    await page.getByRole('menuitem', { name: 'Change role' }).click();
    await pick(page, 'Role', 'Employee');
    await dialog(page).getByRole('button', { name: 'Change role' }).click();
    await expect(formError(page)).toContainText('deactivate the profile before changing the role');
    expect((await seed.admin.get(`/api/admin/users/${u.id}`)).body.role).toBe('TECHNICIAN');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();

    await page.goto('/admin/technicians');
    await find(page, u.name);
    await menu(page, u.name);
    await page.getByRole('menuitem', { name: 'Deactivate profile' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate profile' }).click();
    await toast(page, `Profile of ${u.name} was deactivated.`);
    expect((await seed.admin.get(`/api/admin/technicians/${u.id}`)).body.active).toBe(false);
    const changed = await seed.admin.put(`/api/admin/users/${u.id}/role`, { role: 'EMPLOYEE', version: (await seed.admin.get(`/api/admin/users/${u.id}`)).body.version });
    expect(changed.status).toBe(200);
  });
});
