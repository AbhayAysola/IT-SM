import { test, expect, newUser } from './fixtures';
import { axeViolations, formAlert, settle, signIn, tabStops } from './helpers';

// aria-hidden-focus is skipped ONLY while a modal Radix layer is open: Radix marks the page behind it
// aria-hidden and traps focus, so those elements are unreachable. navigation.spec.ts asserts that
// containment directly (0 of 12 Tab presses escape the open menu).
const MODAL_SKIP = { skip: ['aria-hidden-focus'] };

test.describe('WCAG 2.2 AA (axe), desktop', () => {
  test('sign-in screen', async ({ page }) => {
    await page.goto('/');
    expect(await axeViolations(page)).toEqual([]);
  });

  test('sign-in screen with an error shown', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel('Email address').fill('nobody@example.test');
    await page.getByLabel('Password').fill('wrong-password');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(formAlert(page)).toBeVisible();
    expect(await axeViolations(page)).toEqual([]);
  });

  test('register form', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await expect(page.getByLabel('Full name')).toBeVisible();
    expect(await axeViolations(page)).toEqual([]);
  });

  test('dashboard', async ({ page, api }) => {
    await signIn(page, api, newUser('a11y-dash'));
    expect(await axeViolations(page)).toEqual([]);
  });

  test('dashboard with the account menu open', async ({ page, api }) => {
    await signIn(page, api, newUser('a11y-menu'));
    await page.getByRole('button', { name: /Account menu/ }).click();
    await expect(page.getByRole('menu')).toBeVisible();
    await settle(page);
    expect(await axeViolations(page, MODAL_SKIP)).toEqual([]);
  });
});

test.describe('WCAG 2.2 AA (axe), mobile 390px', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  test('sign-in screen', async ({ page }) => {
    await page.goto('/');
    expect(await axeViolations(page)).toEqual([]);
  });

  test('dashboard with the account menu open', async ({ page, api }) => {
    await signIn(page, api, newUser('a11y-mobile'));
    await page.getByRole('button', { name: /Account menu/ }).click();
    await expect(page.getByRole('menu')).toBeVisible();
    await settle(page);
    expect(await axeViolations(page, MODAL_SKIP)).toEqual([]);
  });
});

test.describe('visible keyboard focus', () => {
  test('every Tab stop on the sign-in screen shows a focus indicator', async ({ page }) => {
    await page.goto('/');
    const stops = await tabStops(page, 5);
    expect(stops.map((s) => s.name)).toEqual(['Sign In', 'Register', 'email', 'password', 'Sign in']);
    expect(stops.filter((s) => !s.visible).map((s) => s.name), 'stops without a focus indicator').toEqual([]);
  });

  test('every Tab stop on the dashboard shows a focus indicator', async ({ page, api }) => {
    await signIn(page, api, newUser('focus-dash'));
    const stops = await tabStops(page, 4);
    expect(stops.length).toBe(4);
    expect(stops.filter((s) => !s.visible).map((s) => s.name), 'stops without a focus indicator').toEqual([]);
  });
});
