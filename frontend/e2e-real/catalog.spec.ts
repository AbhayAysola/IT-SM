import { test, expect, type Page } from '@playwright/test';
import { Seed, uid } from './support/api';
import { admin } from './support/env';
import { dialog, formError, openAdminAs, rowMenu, rows, search, toast } from './support/ui';

/** Categories and IT services through the real UI and API. */

let seed: Seed;
test.beforeAll(async () => { seed = await Seed.asAdmin(admin().email, admin().password); });
test.afterAll(async () => { await seed.dispose(); });

const find = async (page: Page, text: string) => { await search(page, text); await expect(rows(page).filter({ hasText: text })).toHaveCount(1); };
const menu = (page: Page, name: string) => rowMenu(page, new RegExp(`Actions for ${name}`));

test.describe('admin: categories', () => {
  test('adding a category validates, saves keywords normalised, and refuses a duplicate code', async ({ page }) => {
    const code = `RS_ADD_${uid().toUpperCase()}`, name = `RS Added ${uid()}`;
    // keywords are unique across ACTIVE categories, so they must be unique per run (the suite must be re-runnable)
    const k = uid(), kw = [`Print Queue ${k}`, `Toner ${k}`, `Scanner ${k}`];
    await openAdminAs(page, admin(), '/admin/categories');
    await page.getByRole('button', { name: 'Add category' }).first().click();
    await dialog(page).getByRole('button', { name: 'Add category' }).click();
    await expect(dialog(page).getByText('Enter a code.')).toBeVisible();
    await dialog(page).getByLabel('Code').fill(code);
    await dialog(page).getByLabel('Name').fill(name);
    await dialog(page).getByLabel('Keywords').fill(`${kw[0]},  ${kw[1]}\n${kw[2]}`);
    await dialog(page).getByRole('button', { name: 'Add category' }).click();
    await toast(page, `Category “${name}” was added.`);
    await find(page, name);
    const list = (await seed.admin.get(`/api/admin/categories?search=${code}`)).body;
    expect(list.totalItems).toBe(1);
    expect([...list.items[0].keywords].sort()).toEqual(kw.map((x) => x.toLowerCase()).sort());

    await page.getByRole('button', { name: 'Add category' }).first().click();
    await dialog(page).getByLabel('Code').fill(code);
    await dialog(page).getByLabel('Name').fill('Second');
    await dialog(page).getByLabel('Keywords').fill(`dup-${k}`);
    await dialog(page).getByRole('button', { name: 'Add category' }).click();
    await expect(formError(page)).toContainText(`A category with code '${code}' already exists`);
    await expect(dialog(page).getByLabel('Keywords')).toHaveValue(`dup-${k}`);
  });

  test('optimistic lock on a category edit, and deactivate/activate round trip', async ({ page }) => {
    const c = await seed.category('LOCK');
    const k = uid(), elsewhere = `elsewhere-${k}`, mine = [`mine1-${k}`, `mine2-${k}`];
    await openAdminAs(page, admin(), '/admin/categories');
    await find(page, c.name);
    await menu(page, c.name);
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    await expect(dialog(page).getByLabel('Code')).toBeDisabled();
    const other = await seed.admin.put(`/api/admin/categories/${c.id}`, { name: c.name, description: 'edited elsewhere', keywords: [elsewhere], version: c.version });
    expect(other.status).toBe(200);
    await dialog(page).getByLabel('Keywords').fill(mine.join(', '));
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(formError(page)).toContainText('changed by someone else');
    expect((await seed.admin.get(`/api/admin/categories/${c.id}`)).body.keywords).toEqual([elsewhere]);
    await dialog(page).getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, `Category “${c.name}” was updated.`);
    expect([...(await seed.admin.get(`/api/admin/categories/${c.id}`)).body.keywords].sort()).toEqual([...mine].sort());

    await menu(page, c.name);
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate category' }).click();
    await toast(page, `Category “${c.name}” was deactivated.`);
    expect((await seed.admin.get(`/api/admin/categories/${c.id}`)).body.active).toBe(false);
    await menu(page, c.name);
    await page.getByRole('menuitem', { name: 'Activate' }).click();
    await dialog(page).getByRole('button', { name: 'Activate category' }).click();
    await toast(page, `Category “${c.name}” was activated.`);
    expect((await seed.admin.get(`/api/admin/categories/${c.id}`)).body.active).toBe(true);
  });
});

test.describe('admin: IT services', () => {
  test('adding a service stores criticality, owner team and dependencies; a duplicate name is refused', async ({ page }) => {
    const dep = await seed.service('dep');
    const name = `RS Added svc ${uid()}`;
    await openAdminAs(page, admin(), '/admin/services');
    await page.getByRole('button', { name: 'Add service' }).first().click();
    await dialog(page).getByLabel('Name').fill(name);
    await dialog(page).getByLabel('Owner team (optional)').fill('Finance IT');
    await page.getByRole('combobox', { name: 'Business criticality', exact: true }).click();
    await page.getByRole('option', { name: '4 · High', exact: true }).click();
    await dialog(page).getByLabel(dep.name).check();
    await dialog(page).getByRole('button', { name: 'Add service' }).click();
    await toast(page, `Service “${name}” was added.`);
    const s = (await seed.admin.get(`/api/admin/services?search=${encodeURIComponent(name)}`)).body.items[0];
    expect(s.businessCriticality).toBe(4);
    expect(s.ownerTeam).toBe('Finance IT');
    expect(s.dependsOn.map((d: any) => d.id)).toEqual([dep.id]);

    await page.getByRole('button', { name: 'Add service' }).first().click();
    await dialog(page).getByLabel('Name').fill(name.toUpperCase());
    await dialog(page).getByRole('button', { name: 'Add service' }).click();
    await expect(formError(page)).toContainText(/already exists/);
  });

  test('a dependency cycle is refused by the server and the form keeps the selection', async ({ page }) => {
    const a = await seed.service('cycA');
    const b = await seed.service('cycB', [a.id]);          // b depends on a
    await openAdminAs(page, admin(), '/admin/services');
    await search(page, a.name);
    await expect(rows(page).filter({ hasText: a.name })).toHaveCount(1);
    await menu(page, a.name);
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    await dialog(page).getByLabel(b.name).check();         // a -> b would close the loop
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(formError(page)).toContainText('circular dependency');
    await expect(dialog(page).getByLabel(b.name)).toBeChecked();
    expect((await seed.admin.get(`/api/admin/services/${a.id}`)).body.dependsOn).toEqual([]);
  });

  test('optimistic lock on a service edit, and deactivation', async ({ page }) => {
    const s = await seed.service('lock');
    await openAdminAs(page, admin(), '/admin/services');
    await find(page, s.name);
    await menu(page, s.name);
    await page.getByRole('menuitem', { name: 'Edit' }).click();
    const other = await seed.admin.put(`/api/admin/services/${s.id}`, { name: s.name, description: null, businessCriticality: 3, ownerTeam: 'Elsewhere', dependsOnServiceIds: [], version: s.version });
    expect(other.status).toBe(200);
    await dialog(page).getByLabel('Owner team (optional)').fill('Mine');
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(formError(page)).toContainText('changed by someone else');
    expect((await seed.admin.get(`/api/admin/services/${s.id}`)).body.ownerTeam).toBe('Elsewhere');
    await dialog(page).getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, `Service “${s.name}” was updated.`);
    expect((await seed.admin.get(`/api/admin/services/${s.id}`)).body.ownerTeam).toBe('Mine');

    await menu(page, s.name);
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: /^Deactivate/ }).click();
    await toast(page, new RegExp(`Service “${s.name}” was deactivated`));
    expect((await seed.admin.get(`/api/admin/services/${s.id}`)).body.active).toBe(false);
  });
});
