import { expect, type Locator, type Page } from '@playwright/test';
import type { Persona } from './env';

/** Signs in through the real form (no API seeding, no mocks) and waits for the dashboard with the profile loaded. */
export async function uiLogin(page: Page, who: Pick<Persona, 'email' | 'password'>) {
  await page.goto('/');
  await page.getByLabel('Email address').fill(who.email);
  await page.getByLabel('Password').fill(who.password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL('**/dashboard');
  await page.locator('dd').first().waitFor();
}

export async function openAdminAs(page: Page, who: Pick<Persona, 'email' | 'password'>, path: string) {
  await uiLogin(page, who);
  await page.goto(path);
  await expect(page.getByRole('navigation', { name: 'Admin sections' })).toBeVisible();
}

export const dialog = (page: Page) => page.getByRole('dialog');
export const rows = (page: Page) => page.locator('main tbody tr');
export const search = (page: Page, q: string) => page.getByRole('searchbox').fill(q);
export const rowMenu = (page: Page, label: string | RegExp) => page.getByRole('button', { name: typeof label === 'string' ? `Actions for ${label}` : label }).click();
export const toast = (page: Page, text: string | RegExp) => expect(page.getByTestId('toasts').getByText(text).first()).toBeVisible();
export const formError = (page: Page): Locator => dialog(page).getByTestId('form-error');

/** Opens a Radix select by its accessible name and picks an option. */
export async function pick(page: Page, name: string | RegExp, option: string | RegExp) {
  await page.getByRole('combobox', { name, exact: typeof name === 'string' }).click();
  await page.getByRole('option', { name: option, exact: typeof option === 'string' }).click();
}

/** Collects uncaught page exceptions (console 401 noise from the anonymous session probe is expected and ignored). */
export function pageErrors(page: Page) {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message.slice(0, 200)));
  return errors;
}
