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
  test('registration never signs the user in: it shows a neutral notice, then the new account can sign in', async ({ page }) => {
    const user = newUser('register');
    await page.goto('/');
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await page.getByLabel('Full name').fill(user.name);
    await page.getByLabel('Email address').fill(user.email);
    await page.getByLabel('Password').fill(user.password);
    await page.getByRole('button', { name: 'Create account' }).click();

    // 202 with no token: still on the sign-in screen, in sign-in mode, with a status message and nothing stored.
    await expect(page.getByRole('status')).toContainText(/sign in below/i);
    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('button', { name: 'Sign In', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByLabel('Password')).toHaveValue('');
    expect(await page.evaluate(() => localStorage.getItem('it_sm_token'))).toBeNull();

    await page.getByLabel('Password').fill(user.password);
    await submit(page).click();
    await page.waitForURL('**/dashboard');
    await expect(page.locator('dd', { hasText: user.email })).toBeVisible();
  });

  test('registering an address that already has an account shows the SAME notice and does not touch that account', async ({ page, api }) => {
    const user = newUser('dup');
    await api.seed(user);
    await page.goto('/');
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await page.getByLabel('Full name').fill('Someone Else');
    await page.getByLabel('Email address').fill(user.email);
    await page.getByLabel('Password').fill('another-password');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(page.getByRole('status')).toContainText(/sign in below/i);
    await expect(formAlert(page)).toHaveCount(0);                       // no "already registered" signal
    await expect(page.getByText(/already registered|already exists|in use/i)).toHaveCount(0);
    await expect(page).toHaveURL(/\/$/);

    // The attacker's password does not work; the owner's still does.
    await page.getByLabel('Password').fill('another-password');
    await submit(page).click();
    await expect(formAlert(page)).toContainText(/invalid email or password/i);
    await page.getByLabel('Password').fill(user.password);
    await submit(page).click();
    await page.waitForURL('**/dashboard');
  });

  test('a rate-limited sign-in or registration shows a calm message, not a raw error', async ({ page, api }) => {
    test.skip(api.live, 'Throttling is simulated by the hermetic fake; the real limiter is covered by the backend suite.');
    api.authThrottle.on = true;
    await page.goto('/');
    await page.getByLabel('Email address').fill('someone@example.test');
    await page.getByLabel('Password').fill('whatever-pw');
    await submit(page).click();
    await expect(formAlert(page)).toContainText(/too many attempts/i);
    await expect(formAlert(page)).not.toContainText(/429|RATE_LIMITED|exception/i);
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await page.getByLabel('Full name').fill('Some One');
    await page.getByLabel('Email address').fill('someone@example.test');
    await page.getByLabel('Password').fill('whatever-pw');
    await page.getByRole('button', { name: 'Create account' }).click();
    await expect(formAlert(page)).toContainText(/too many attempts/i);
  });

  test('sign in with an existing account, then an authenticated visit to / goes to the dashboard', async ({ page, api }) => {
    await signIn(page, api, newUser('login'));
    await page.goto('/');
    await page.waitForURL('**/dashboard');
  });

  test('the session is invisible to scripts: no token in localStorage, sessionStorage or document.cookie', async ({ page, api, context }) => {
    await signIn(page, api, newUser('storage'));
    const visible = await page.evaluate(() => ({
      local: Object.entries({ ...localStorage }),
      session: Object.entries({ ...sessionStorage }),
      cookie: document.cookie,
    }));
    const dump = JSON.stringify(visible);
    expect(visible.local, dump).toEqual([]);                     // no token, no cached user
    expect(visible.session, dump).toEqual([]);
    expect(visible.cookie, dump).toBe('');                       // an HttpOnly cookie never shows up here
    expect(dump).not.toMatch(/eyJ|mock\.|token/i);              // nor anything that looks like a JWT
    expect(await page.evaluate(() => localStorage.getItem('it_sm_token'))).toBeNull();
    if (api.live) {
      // Against the real backend the session cookie exists, and exactly with the attributes the backend promises.
      const cookie = (await context.cookies()).find((c) => c.name === 'itsm_session');
      expect(cookie, 'session cookie missing').toBeTruthy();
      expect(cookie!.httpOnly).toBe(true);
      expect(cookie!.sameSite).toBe('Strict');
      expect(cookie!.path).toBe('/api');
      expect(cookie!.secure).toBe(false);                        // plain-http local development only (dev profile)
    }
  });

  test('sign-in goes out in browser mode and the answer carries no token', async ({ page, api }) => {
    const user = newUser('mode');
    await api.seed(user);
    await page.goto('/');
    await page.getByLabel('Email address').fill(user.email);
    await page.getByLabel('Password').fill(user.password);
    const loginReq = page.waitForRequest((r) => r.url().endsWith('/api/auth/login') && r.method() === 'POST');
    const loginRes = page.waitForResponse((r) => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST');
    await submit(page).click();
    expect((await loginReq).headers()['x-itsm-client']).toBe('web');
    const body = await (await loginRes).json();
    expect(Object.keys(body)).toEqual(['user']);
    await page.waitForURL('**/dashboard');
  });

  test('a reload keeps the session: the page asks /api/users/me and shows the profile', async ({ page, api }) => {
    const user = newUser('reload');
    await signIn(page, api, user);
    const me = page.waitForResponse((r) => r.url().endsWith('/api/users/me') && r.request().method() === 'GET');
    await page.reload();
    expect((await me).status()).toBe(200);
    await expect(page).toHaveURL(/\/dashboard$/);
    await expect(page.locator('dd', { hasText: user.email })).toBeVisible();
  });

  test('signing out ends the session on the server: the dashboard is guarded and the old session no longer works', async ({ page, api }) => {
    const user = newUser('revoke');
    await signIn(page, api, user);
    const logout = page.waitForResponse((r) => r.url().endsWith('/api/auth/logout') && r.request().method() === 'POST');
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    expect((await logout).status()).toBe(200);
    await page.waitForURL(/\/$/);
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);          // /api/users/me now answers 401
    if (!api.live) expect(api.sessionLog.logouts).toBe(1);
  });

  test('a session that ends on the server sends the next visit to sign-in, and no cached user pretends otherwise', async ({ page, api }) => {
    const user = newUser('expired');
    await signIn(page, api, user);
    await page.getByRole('button', { name: /Account menu/ }).waitFor();
    await api.expireSession(user);
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);
    await expect(page.getByLabel('Email address')).toBeVisible();
    await expect(page.locator('dd', { hasText: user.email })).toHaveCount(0);
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage }))).toBe('{}');
  });

  test('a leftover token or user from an older version is removed and never trusted', async ({ page, context }) => {
    await context.addInitScript(() => {
      if (!sessionStorage.getItem('seeded')) {
        sessionStorage.setItem('seeded', '1');
        localStorage.setItem('it_sm_token', 'old.token.value');
        localStorage.setItem('it_sm_user', JSON.stringify({ id: 1, email: 'old@example.test', name: 'Old', role: 'ADMIN', createdAt: '' }));
        document.cookie = 'it_sm_token=old.token.value; path=/';
      }
    });
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);               // storage did not authenticate anything
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage }))).toBe('{}');
    expect(await page.evaluate(() => document.cookie)).not.toContain('it_sm_token');
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

  test('Sign out from the account menu ends the session and returns to /', async ({ page, api }) => {
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
