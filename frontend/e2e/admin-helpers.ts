import type { Page } from '@playwright/test';
import { expect, newAdmin, type ApiHelper, type TestUser } from './fixtures';
import { signIn } from './helpers';

/** Signs in as an administrator through the real form, then opens an admin route. */
export async function openAdmin(page: Page, api: ApiHelper, path = '/admin', admin: TestUser = newAdmin('admin')) {
  await signIn(page, api, admin);
  await page.goto(path);
  await expect(page.getByRole('navigation', { name: 'Admin sections' })).toBeVisible();
}

/** Registers the signed-in administrator in the fake user store, so self-protection rules can apply to them. */
export function registerSelf(api: ApiHelper, admin: TestUser) {
  return api.admin.addUser(admin.name, 'ADMIN', admin.email);
}

export const rows = (page: Page) => page.locator('main tbody tr');
export const dialog = (page: Page) => page.getByRole('dialog');
export const search = (page: Page, q: string) => page.getByRole('searchbox').fill(q);
export const rowMenu = (page: Page, label: string) => page.getByRole('button', { name: `Actions for ${label}` }).click();
export const toast = (page: Page, text: string | RegExp) => expect(page.getByTestId('toasts').getByText(text).first()).toBeVisible();
/** The table row whose first cell starts with `name` (not rows that merely mention it, e.g. as a dependency). */
export const rowFor = (page: Page, name: string) => page.locator('main tbody tr').filter({ has: page.locator('td:first-child', { hasText: new RegExp(`^\\s*${name}`) }) });
export const summary = (page: Page) => page.getByTestId('pagination-summary');

/** Opens a Radix select by its accessible name and picks an option. */
export async function pick(page: Page, name: string | RegExp, option: string | RegExp) {
  await page.getByRole('combobox', { name, exact: typeof name === 'string' }).click();
  await page.getByRole('option', { name: option, exact: typeof option === 'string' }).click();
}
