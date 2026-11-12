import { test, expect } from '@playwright/test';
import { Api, Seed, randomPassword, uid, uniqueEmail, withCookie } from './support/api';
import { admin } from './support/env';
import { dialog, formError, openAdminAs, pick, rowMenu, rows, search, toast } from './support/ui';
import { expectNo } from './support/safe';

/** User administration through the real UI and the real API. Every test makes its own users. */

let seed: Seed;
test.beforeAll(async () => { seed = await Seed.asAdmin(admin().email, admin().password); });
test.afterAll(async () => { await seed.dispose(); });

const row = (page: import('@playwright/test').Page, email: string) => rows(page).filter({ hasText: email });
const find = async (page: import('@playwright/test').Page, email: string) => { await search(page, email); await expect(row(page, email)).toHaveCount(1); };

test.describe('admin: users', () => {
  test('adding a user through the form creates a real account that can sign in, with the chosen role', async ({ page }) => {
    const email = uniqueEmail('added'), password = randomPassword(), name = `RS Added ${uid()}`;
    await openAdminAs(page, admin(), '/admin/users');
    await page.getByRole('button', { name: 'Add user' }).first().click();
    await dialog(page).getByLabel('Full name').fill(name);
    await dialog(page).getByLabel('Email address').fill(email);
    await dialog(page).getByLabel('Initial password').fill(password);
    await dialog(page).getByRole('button', { name: 'Add user' }).click();
    await toast(page, `${name} was added.`);
    await find(page, email);
    await expect(row(page, email)).toContainText('Employee');
    expectNo(await page.locator('main').innerText(), password, 'the initial password must not be shown on the page');
    const signedIn = await Api.login(email, password);
    expect((await signedIn.get('/api/users/me')).body.role).toBe('EMPLOYEE');
    await signedIn.dispose();
  });

  test('a duplicate email is refused with an explanation and nothing typed is lost', async ({ page }) => {
    const existing = await seed.user('dup');
    await openAdminAs(page, admin(), '/admin/users');
    await page.getByRole('button', { name: 'Add user' }).first().click();
    await dialog(page).getByLabel('Full name').fill('Another Person');
    await dialog(page).getByLabel('Email address').fill(existing.email.toUpperCase());
    await dialog(page).getByLabel('Initial password').fill('long-enough-1');
    await dialog(page).getByRole('button', { name: 'Add user' }).click();
    await expect(formError(page)).toContainText(/already/i);
    await expect(dialog(page).getByLabel('Full name')).toHaveValue('Another Person');
    await expect(dialog(page).getByLabel('Initial password')).toHaveValue('long-enough-1');
  });

  test('changing a role works in the UI and takes effect on the account; the user list shows no secrets', async ({ page }) => {
    const u = await seed.user('role');
    await openAdminAs(page, admin(), '/admin/users');
    await find(page, u.email);
    await rowMenu(page, new RegExp(`Actions for ${u.name}`));
    await page.getByRole('menuitem', { name: 'Change role' }).click();
    await pick(page, 'Role', 'Technician');
    await dialog(page).getByRole('button', { name: 'Change role' }).click();
    await toast(page, `${u.name} is now Technician.`);
    await expect(row(page, u.email)).toContainText('Technician');
    expect((await seed.admin.get(`/api/admin/users/${u.id}`)).body.role).toBe('TECHNICIAN');
    expectNo(await page.locator('main').innerText(), /password|hash|token/i, 'the user list must show no secrets');
  });

  test('deactivating locks the account out of sign-in (and ends its sessions); activating restores it', async ({ page }) => {
    const u = await seed.user('toggle');
    const live = await Api.login(u.email, u.password);
    const liveCookie = (await live.sessionCookie())!;
    await openAdminAs(page, admin(), '/admin/users');
    await find(page, u.email);
    await rowMenu(page, new RegExp(`Actions for ${u.name}`));
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await expect(dialog(page)).toContainText('Their history is kept');
    await dialog(page).getByRole('button', { name: 'Deactivate user' }).click();
    await toast(page, `${u.name} was deactivated.`);
    await expect(row(page, u.email)).toContainText('Inactive');

    const blocked = await Api.anonymous();
    expect((await blocked.send('POST', '/api/auth/login', { email: u.email, password: u.password })).status).toBe(401);
    expect((await (await withCookie(liveCookie)).get('/api/users/me')).status, 'the existing session must stop working').toBe(401);

    await rowMenu(page, new RegExp(`Actions for ${u.name}`));
    await page.getByRole('menuitem', { name: 'Activate' }).click();
    await dialog(page).getByRole('button', { name: 'Activate user' }).click();
    await toast(page, `${u.name} was activated.`);
    expect((await blocked.send('POST', '/api/auth/login', { email: u.email, password: u.password })).status).toBe(200);
    await blocked.dispose(); await live.dispose();
  });

  test('resetting a password replaces it: the old one stops working, the new one works, and old sessions end', async ({ page }) => {
    const u = await seed.user('reset');
    const old = await Api.login(u.email, u.password);
    const oldCookie = (await old.sessionCookie())!;
    const next = randomPassword();
    await openAdminAs(page, admin(), '/admin/users');
    await find(page, u.email);
    await rowMenu(page, new RegExp(`Actions for ${u.name}`));
    await page.getByRole('menuitem', { name: 'Reset password' }).click();
    await expect(dialog(page)).toContainText('never shown again');
    await dialog(page).getByLabel('New password').fill(next);
    await dialog(page).getByRole('button', { name: 'Reset password' }).click();
    await toast(page, new RegExp(`Password reset for ${u.name}`));
    expectNo(await page.locator('main').innerText(), next, 'the new password must not be shown on the page');

    const anon = await Api.anonymous();
    expect((await anon.send('POST', '/api/auth/login', { email: u.email, password: u.password })).status).toBe(401);
    expect((await anon.send('POST', '/api/auth/login', { email: u.email, password: next })).status).toBe(200);
    expect((await (await withCookie(oldCookie)).get('/api/users/me')).status).toBe(401);
    await anon.dispose(); await old.dispose();
  });

  test('revoking sessions ends the user’s sessions but leaves the account usable', async ({ page }) => {
    const u = await seed.user('revoke');
    const s = await Api.login(u.email, u.password);
    const cookie = (await s.sessionCookie())!;
    await openAdminAs(page, admin(), '/admin/users');
    await find(page, u.email);
    await rowMenu(page, new RegExp(`Actions for ${u.name}`));
    await page.getByRole('menuitem', { name: 'Revoke sessions' }).click();
    await dialog(page).getByRole('button', { name: 'Revoke sessions' }).click();
    await toast(page, `Sessions of ${u.name} were revoked.`);
    expect((await (await withCookie(cookie)).get('/api/users/me')).status).toBe(401);
    const again = await Api.anonymous();
    expect((await again.send('POST', '/api/auth/login', { email: u.email, password: u.password })).status).toBe(200);
    await again.dispose(); await s.dispose();
  });

  test('optimistic lock: someone else saves first, the stale save is refused, "keep my edits" then succeeds', async ({ page }) => {
    const u = await seed.user('stale');
    await openAdminAs(page, admin(), '/admin/users');
    await find(page, u.email);
    await rowMenu(page, new RegExp(`Actions for ${u.name}`));
    await page.getByRole('menuitem', { name: 'Edit details' }).click();
    // a concurrent edit through the real API bumps the version under the open dialog
    const other = await seed.admin.put(`/api/admin/users/${u.id}`, { name: `${u.name} (other)`, email: u.email, version: u.version });
    expect(other.status).toBe(200);
    const mine = `${u.name} (mine)`;
    await dialog(page).getByLabel('Full name').fill(mine);
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await expect(formError(page)).toContainText('changed by someone else');
    await expect(dialog(page).getByLabel('Full name')).toHaveValue(mine);
    expect((await seed.admin.get(`/api/admin/users/${u.id}`)).body.name, 'the stale save must not have been applied').toBe(`${u.name} (other)`);
    await dialog(page).getByRole('button', { name: 'Keep my edits on the latest version' }).click();
    await dialog(page).getByRole('button', { name: 'Save changes' }).click();
    await toast(page, `${mine} was updated.`);
    expect((await seed.admin.get(`/api/admin/users/${u.id}`)).body.name).toBe(mine);
  });

  test('a stale deactivate is refused, then works once confirmed against the refreshed version', async ({ page }) => {
    const u = await seed.user('stale2');
    await openAdminAs(page, admin(), '/admin/users');
    await find(page, u.email);
    await rowMenu(page, new RegExp(`Actions for ${u.name}`));
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    expect((await seed.admin.put(`/api/admin/users/${u.id}`, { name: `${u.name} x`, email: u.email, version: u.version })).status).toBe(200);
    await dialog(page).getByRole('button', { name: 'Deactivate user' }).click();
    await expect(dialog(page)).toContainText('changed by someone else');
    expect((await seed.admin.get(`/api/admin/users/${u.id}`)).body.active).toBe(true);
    await dialog(page).getByRole('button', { name: 'Deactivate user' }).click();
    await toast(page, /was deactivated\./);
    expect((await seed.admin.get(`/api/admin/users/${u.id}`)).body.active).toBe(false);
  });

  test('self-protection: an administrator cannot deactivate or demote themselves', async ({ page }) => {
    await openAdminAs(page, admin(), '/admin/users');
    await find(page, admin().email);
    await rowMenu(page, /Actions for Administrator/);
    await page.getByRole('menuitem', { name: 'Deactivate' }).click();
    await dialog(page).getByRole('button', { name: 'Deactivate user' }).click();
    await expect(dialog(page)).toContainText('You cannot deactivate your own account');
    await dialog(page).getByRole('button', { name: 'Cancel' }).click();
    await rowMenu(page, /Actions for Administrator/);
    await page.getByRole('menuitem', { name: 'Change role' }).click();
    await pick(page, 'Role', 'Employee');
    await dialog(page).getByRole('button', { name: 'Change role' }).click();
    await expect(formError(page)).toContainText('You cannot change your own administrator role');
    const me = await seed.admin.get(`/api/admin/users/${admin().id}`);
    expect(me.body.role).toBe('ADMIN');
    expect(me.body.active).toBe(true);
  });

  test('there is no delete action, and the server has no user delete route', async ({ page }) => {
    const u = await seed.user('nodelete');
    await openAdminAs(page, admin(), '/admin/users');
    await find(page, u.email);
    await rowMenu(page, new RegExp(`Actions for ${u.name}`));
    await expect(page.getByRole('menuitem', { name: /delete/i })).toHaveCount(0);
    const r = await seed.admin.send('DELETE', `/api/admin/users/${u.id}`);
    expect([404, 405]).toContain(r.status);
    expect((await seed.admin.get(`/api/admin/users/${u.id}`)).status).toBe(200);
  });
});
