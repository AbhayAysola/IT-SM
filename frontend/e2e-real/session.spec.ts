import { test, expect } from '@playwright/test';
import { Api, withCookie } from './support/api';
import { COOKIE_NAME, WEB_ORIGIN, admin, employee } from './support/env';
import { pageErrors, uiLogin } from './support/ui';
import { expectNo, JWT } from './support/safe';

/** Browser authentication against the real backend: the HttpOnly cookie, reload, logout and server-side revocation. */

const sessionCookie = async (page: import('@playwright/test').Page) => (await page.context().cookies()).find((c) => c.name === COOKIE_NAME);

test.describe('browser session', () => {
  test('signing in sets an HttpOnly, SameSite=Strict, Path=/api cookie and the page can read no token anywhere', async ({ page }) => {
    const errors = pageErrors(page);
    await uiLogin(page, employee());
    const c = await sessionCookie(page);
    expect(c, 'the session cookie must exist').toBeTruthy();
    expect(c!.httpOnly).toBe(true);
    expect(c!.sameSite).toBe('Strict');
    expect(c!.path).toBe('/api');
    expect(c!.secure, 'CI is plain HTTP: the dev profile allows a non-Secure cookie; production refuses it (backend suite)').toBe(false);
    const lifetime = c!.expires - Date.now() / 1000;
    expect(lifetime).toBeGreaterThan(23 * 3600);
    expect(lifetime).toBeLessThanOrEqual(24 * 3600 + 60);
    // scripts cannot see it, and nothing token-shaped is stored by the app
    const visible = await page.evaluate(() => ({ cookie: document.cookie, local: JSON.stringify({ ...localStorage }), session: JSON.stringify({ ...sessionStorage }) }));
    expectNo(visible.cookie, COOKIE_NAME, 'document.cookie must not expose the session cookie');
    for (const [where, text] of Object.entries(visible)) {
      expectNo(text, JWT, `${where} must not hold a JWT`);
      expectNo(text, /it_sm_token|it_sm_user/, `${where} must not hold the legacy token keys`);
    }
    expect(errors).toEqual([]);
  });

  test('the browser-mode login response carries the user and never a token', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel('Email address').fill(employee().email);
    await page.getByLabel('Password').fill(employee().password);
    const [res] = await Promise.all([
      page.waitForResponse((r) => r.url().endsWith('/api/auth/login') && r.request().method() === 'POST'),
      page.getByRole('button', { name: 'Sign in', exact: true }).click(),
    ]);
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(Object.keys(body)).toEqual(['user']);
    expect(body.user.role).toBe('EMPLOYEE');
    expectNo(JSON.stringify(body), /eyJ|token|password/i, 'the login response must not carry a token or password');
    expect((await res.allHeaders())['set-cookie'] ?? '').toContain('HttpOnly');
  });

  test('a reload keeps the session: the app asks the server who the user is', async ({ page }) => {
    await uiLogin(page, employee());
    const me = page.waitForResponse((r) => r.url().endsWith('/api/users/me') && r.status() === 200);
    await page.reload();
    await me;
    await expect(page).toHaveURL(/\/dashboard$/);
    await page.locator('dd').first().waitFor();
    await expect(page.getByText(employee().email).first()).toBeVisible();
  });

  test('a protected page without a session redirects to sign-in and calls only /api/users/me', async ({ page }) => {
    const calls: string[] = [];
    page.on('request', (r) => { if (r.url().includes('/api/')) calls.push(new URL(r.url()).pathname); });
    await page.goto('/admin/users');
    await page.waitForURL(/\/\?redirect=%2Fadmin%2Fusers/);
    expect(calls).toEqual(['/api/users/me']);
  });

  test('a wrong password shows the error, sets no cookie and costs nothing else', async ({ page }) => {
    await page.goto('/');
    await page.getByLabel('Email address').fill(employee().email);
    await page.getByLabel('Password').fill('Wrong-Password-1!');
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.locator('main [role=alert]')).toBeVisible();
    await expect(page).toHaveURL(/\/$/);
    expect(await sessionCookie(page)).toBeUndefined();
  });

  test('signing out ends the session on the SERVER: the old cookie is refused, and so is every other session of that user', async ({ page, browser }) => {
    // a second browser session of the same user, signed in first
    const other = await browser.newContext();
    const otherPage = await other.newPage();
    await uiLogin(otherPage, employee());
    const otherCookie = (await other.cookies()).find((c) => c.name === COOKIE_NAME)!.value;

    await uiLogin(page, employee());
    const cookie = (await sessionCookie(page))!.value;
    // the replay mechanism works while the session is live (so the 401 below is not vacuous)
    expect((await (await withCookie(cookie)).get('/api/users/me')).status).toBe(200);
    expect((await (await withCookie(otherCookie)).get('/api/users/me')).status).toBe(200);

    await page.getByRole('button', { name: /Account menu/ }).click();
    await page.getByRole('menuitem', { name: 'Sign out' }).click();
    await page.waitForURL(/\/(\?.*)?$/);

    expect(await sessionCookie(page), 'the browser cookie is cleared').toBeUndefined();
    expect((await (await withCookie(cookie)).get('/api/users/me')).status, 'the old cookie must be refused by the server').toBe(401);
    // documented: logout bumps token_version, which revokes ALL of the user's sessions
    expect((await (await withCookie(otherCookie)).get('/api/users/me')).status).toBe(401);
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);
    await other.close();
  });

  test('the next sign-in after a sign-out works (the revocation is not permanent)', async ({ page }) => {
    await uiLogin(page, employee());
    await expect(page).toHaveURL(/\/dashboard$/);
  });
});

test.describe('the real security chain, as a browser client would meet it', () => {
  test('cookie-authenticated writes need the web-client header AND an allowed Origin; a Bearer client needs neither', async () => {
    const a = await Api.login(employee().email, employee().password);
    const cookie = (await a.sessionCookie())!;
    const send = async (headers: Record<string, string>) => {
      const bare = await Api.anonymous({ Cookie: `${COOKIE_NAME}=${cookie}`, ...headers });
      const r = await bare.send('POST', '/api/auth/logout', {});
      await bare.dispose();
      return r;
    };
    // each of these must be refused with the same generic 403 and must NOT have logged the user out
    const refused: Array<Record<string, string>> = [
      { Origin: WEB_ORIGIN },                                                  // no X-ITSM-Client
      { 'X-ITSM-Client': 'web' },                                              // no Origin
      { 'X-ITSM-Client': 'web', Origin: 'http://evil.example' },               // foreign Origin
      { 'X-ITSM-Client': 'web', Origin: 'null' },
    ];
    for (const headers of refused) {
      const r = await send(headers);
      expect(r.status, JSON.stringify(Object.keys(headers))).toBe(403);
    }
    const generic = await send({ Origin: WEB_ORIGIN });
    expect(generic.body?.detail ?? generic.body?.message).toBe('Request rejected');
    expect((await a.get('/api/users/me')).status, 'none of the refused requests may have had an effect').toBe(200);

    // a malformed Origin is refused before CORS/authentication with a generic 400, never a 500
    const malformed = await send({ 'X-ITSM-Client': 'web', Origin: 'http://localhost:3000.evil.example' });
    expect(malformed.status).toBe(400);
    expectNo(JSON.stringify(malformed.body), /Exception|evil\.example|localhost/, 'the malformed-Origin refusal must not leak parser or allow-list details');

    // a Bearer client (no cookie, no Origin) is not subject to the cookie CSRF rules
    const bearer = await Api.anonymous({});
    const login = await bearer.send('POST', '/api/auth/login', { email: employee().email, password: employee().password });
    expect(login.status).toBe(200);
    expect(login.body.token).toBeTruthy();
    expect(login.headers['set-cookie']).toBeUndefined();
    const out = await bearer.send('POST', '/api/auth/logout', {}, { Authorization: `Bearer ${login.body.token}` });
    expect(out.status).toBe(200);
    await bearer.dispose(); await a.dispose();
  });

  test('an invalid Authorization header never falls back to a valid session cookie', async () => {
    const a = await Api.login(employee().email, employee().password);
    const cookie = (await a.sessionCookie())!;
    const probe = await Api.anonymous({ Cookie: `${COOKIE_NAME}=${cookie}` });
    expect((await probe.send('GET', '/api/users/me')).status).toBe(200);
    expect((await probe.send('GET', '/api/users/me', undefined, { Authorization: 'Bearer not-a-valid-token' })).status).toBe(401);
    expect((await probe.send('GET', '/api/users/me', undefined, { Authorization: 'Basic Zm9vOmJhcg==' })).status).toBe(401);
    await probe.dispose(); await a.dispose();
  });

  test('CORS: a foreign origin gets no grant and no credentials; the app origin gets both', async () => {
    const anon = await Api.anonymous({});
    const pre = (origin: string) => anon.send('OPTIONS', '/api/users/me', undefined, { Origin: origin, 'Access-Control-Request-Method': 'GET' });
    const foreign = await pre('http://evil.example');
    expect(foreign.status).toBe(403);
    expect(foreign.headers['access-control-allow-origin']).toBeUndefined();
    expect(foreign.headers['access-control-allow-credentials']).toBeUndefined();
    const own = await pre(WEB_ORIGIN);
    expect(own.headers['access-control-allow-origin']).toBe(WEB_ORIGIN);
    expect(own.headers['access-control-allow-credentials']).toBe('true');
    await anon.dispose();
  });

  test('the web login without the app Origin is refused (login CSRF), and sets no cookie', async () => {
    const e = employee();
    const noOrigin = await Api.anonymous({ 'X-ITSM-Client': 'web' });
    const r = await noOrigin.send('POST', '/api/auth/login', { email: e.email, password: e.password });
    expect(r.status).toBe(403);
    expect(r.headers['set-cookie']).toBeUndefined();
    await noOrigin.dispose();
  });

  test('admin sessions work the same way (the cookie authenticates the admin API)', async () => {
    const a = await Api.login(admin().email, admin().password);
    expect((await a.get('/api/admin/users?size=1')).status).toBe(200);
    await a.dispose();
  });
});
