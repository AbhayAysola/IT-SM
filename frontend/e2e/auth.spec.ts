import { test, expect, newUser } from './fixtures';
import { formAlert, signIn } from './helpers';

const submit = (page: import('@playwright/test').Page) => page.getByRole('button', { name: 'Sign in', exact: true });

test.describe('sign-in screen', () => {
  test('renders title and form, with no demo-credentials shortcut', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/IT-SM/);
    await expect(page.getByRole('heading', { name: 'IT-SM' })).toBeVisible();
    await expect(page.getByLabel('Email address')).toBeVisible();
    await expect(page.getByLabel('Password')).toBeVisible();
    await expect(page.getByText(/demo credentials/i)).toHaveCount(0);
    await expect(page.getByText(/admin@example\.com/i)).toHaveCount(0);
  });

  test('wrong credentials show the backend error in an alert', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel('Email address').fill('nobody@example.test');
    await page.getByLabel('Password').fill('wrong-password');
    await submit(page).click();
    await expect(formAlert(page)).toContainText(/invalid email or password/i);
    await expect(page).toHaveURL(/\/$/);
  });

  test('Register toggle reveals Full name, exposes pressed state, and clears a previous error', async ({ page }) => {
    await page.goto('/');
    const signInTab = page.getByRole('button', { name: 'Sign In', exact: true });
    const registerTab = page.getByRole('button', { name: 'Register', exact: true });
    await expect(signInTab).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByLabel('Full name')).toHaveCount(0);

    await page.getByLabel('Email address').fill('nobody@example.test');
    await page.getByLabel('Password').fill('wrong-password');
    await submit(page).click();
    await expect(formAlert(page)).toBeVisible();

    await registerTab.click();
    await expect(registerTab).toHaveAttribute('aria-pressed', 'true');
    await expect(signInTab).toHaveAttribute('aria-pressed', 'false');
    await expect(page.getByLabel('Full name')).toBeVisible();
    await expect(formAlert(page)).toHaveCount(0);
  });

  test('uses the right autocomplete hints for identity fields', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByLabel('Email address')).toHaveAttribute('autocomplete', 'email');
    await expect(page.getByLabel('Password')).toHaveAttribute('autocomplete', 'current-password');
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await expect(page.getByLabel('Password')).toHaveAttribute('autocomplete', 'new-password');
    await expect(page.getByLabel('Full name')).toHaveAttribute('autocomplete', 'name');
  });
});

test.describe('registration, sign-in and sign-out', () => {
  test('register through the UI lands on the dashboard with the profile and a stored token', async ({ page }) => {
    const user = newUser('register');
    await page.goto('/');
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await page.getByLabel('Full name').fill(user.name);
    await page.getByLabel('Email address').fill(user.email);
    await page.getByLabel('Password').fill(user.password);
    await page.getByRole('button', { name: 'Create account' }).click();

    await page.waitForURL('**/dashboard');
    await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
    await expect(page.locator('dd', { hasText: user.name })).toBeVisible();
    await expect(page.locator('dd', { hasText: user.email })).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('it_sm_token'))).toBeTruthy();
  });

  test('duplicate registration shows the 409 message', async ({ page, api }) => {
    const user = newUser('dup');
    await api.seed(user);
    await page.goto('/');
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await page.getByLabel('Full name').fill('Someone Else');
    await page.getByLabel('Email address').fill(user.email);
    await page.getByLabel('Password').fill('another-password');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(formAlert(page)).toContainText(/already registered/i);
    await expect(page).toHaveURL(/\/$/);
  });

  test('sign in with an existing account, then an authenticated visit to / goes to the dashboard', async ({ page, api }) => {
    await signIn(page, api, newUser('login'));
    await page.goto('/');
    await page.waitForURL('**/dashboard');
  });

  test('over plain-http localhost the auth cookie is still set, without Secure (Secure is HTTPS-only)', async ({ page, api, context }) => {
    await signIn(page, api, newUser('cookie'));
    const cookie = (await context.cookies()).find((c) => c.name === 'it_sm_token');
    expect(cookie, 'auth cookie missing over http').toBeTruthy();
    expect(cookie!.secure).toBe(false);
    expect(cookie!.sameSite).toBe('Lax');
  });

  test('keyboard-only sign-in (type, Tab, type, Enter)', async ({ page, api }) => {
    const user = newUser('kbd');
    await api.seed(user);
    await page.goto('/');
    await page.getByLabel('Email address').focus();
    await page.keyboard.type(user.email);
    await page.keyboard.press('Tab');
    await page.keyboard.type(user.password);
    await page.keyboard.press('Enter');
    await page.waitForURL('**/dashboard');
  });

  test('Sign out from the account menu clears the token and returns to /', async ({ page, api }) => {
    await signIn(page, api, newUser('out-menu'));
    await page.getByRole('button', { name: /Account menu/ }).click();
    await page.getByRole('menuitem', { name: 'Sign out' }).click();
    await page.waitForURL(/\/$/);
    expect(await page.evaluate(() => localStorage.getItem('it_sm_token'))).toBeNull();
  });

  test('Sign out from the header icon button works too', async ({ page, api }) => {
    await signIn(page, api, newUser('out-icon'));
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.waitForURL(/\/$/);
    expect(await page.evaluate(() => localStorage.getItem('it_sm_token'))).toBeNull();
  });

  test('after signing out the dashboard is guarded again', async ({ page, api }) => {
    await signIn(page, api, newUser('out-guard'));
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await page.waitForURL(/\/$/);
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);
  });
});
