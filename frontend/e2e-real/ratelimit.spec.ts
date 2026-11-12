import { test, expect } from '@playwright/test';
import { Api, Seed, randomPassword, uid, uniqueEmail } from './support/api';
import { admin, employee } from './support/env';
import { expectNo } from './support/safe';

/**
 * The per-email login limit (production value: 5 failures per IP+email per window) and registration, on the real backend.
 * Only the IP-WIDE limits are raised in this job, so this limit is exactly what production ships.
 * The register-per-IP limit (10/hour) is NOT exercised here (it is raised for the suite; the backend suite covers it).
 */

let seed: Seed;
test.beforeAll(async () => { seed = await Seed.asAdmin(admin().email, admin().password); });
test.afterAll(async () => { await seed.dispose(); });

test.describe('login throttling', () => {
  test('five wrong passwords are 401, the sixth attempt is 429 with a Retry-After, and even the right password is then refused', async () => {
    const u = await seed.user('thr');
    const anon = await Api.anonymous();
    const statuses: number[] = [];
    for (let i = 0; i < 5; i++) statuses.push((await anon.send('POST', '/api/auth/login', { email: u.email, password: `Wrong-${uid()}-1!` })).status);
    expect(statuses).toEqual([401, 401, 401, 401, 401]);
    const blocked = await anon.send('POST', '/api/auth/login', { email: u.email, password: `Wrong-${uid()}-1!` });
    expect(blocked.status).toBe(429);
    expect(blocked.body.code).toBe('RATE_LIMITED');
    expect(Number(blocked.headers['retry-after'])).toBeGreaterThan(0);
    expect(JSON.stringify(blocked.body)).not.toContain(u.email);
    expect(blocked.headers['set-cookie']).toBeUndefined();
    const right = await anon.send('POST', '/api/auth/login', { email: u.email, password: u.password });
    expect(right.status, 'a blocked pair stays blocked until the window ends').toBe(429);
    await anon.dispose();
  });

  test('the limit is per account: other accounts on the same address are unaffected', async () => {
    const victim = await seed.user('thr2');
    const anon = await Api.anonymous();
    for (let i = 0; i < 6; i++) await anon.send('POST', '/api/auth/login', { email: victim.email, password: `Wrong-${uid()}-1!` });
    const other = await anon.send('POST', '/api/auth/login', { email: employee().email, password: employee().password });
    expect(other.status).toBe(200);
    await anon.dispose();
  });

  test('the browser shows the calm rate-limit message and sets no cookie', async ({ page }) => {
    const u = await seed.user('thr3');
    const anon = await Api.anonymous();
    for (let i = 0; i < 6; i++) await anon.send('POST', '/api/auth/login', { email: u.email, password: `Wrong-${uid()}-1!` });
    await anon.dispose();
    await page.goto('/');
    await page.getByLabel('Email address').fill(u.email);
    await page.getByLabel('Password').fill(u.password);
    await page.getByRole('button', { name: 'Sign in', exact: true }).click();
    await expect(page.locator('main [role=alert]')).toContainText('Too many attempts');
    await expect(page).toHaveURL(/\/$/);
    expect((await page.context().cookies()).find((c) => c.name === 'itsm_session')).toBeUndefined();
  });

  test('a successful sign-in does not count against the limit', async () => {
    const u = await seed.user('thr4');
    const anon = await Api.anonymous();
    for (let i = 0; i < 8; i++) expect((await anon.send('POST', '/api/auth/login', { email: u.email, password: u.password })).status).toBe(200);
    await anon.dispose();
  });
});

test.describe('this job\'s throttle configuration binds to the intended properties', () => {
  // Defaults are 40 failed logins and 10 registrations per IP per window. This job raises ONLY those two. If the startup arguments
  // did not bind (a typo in a property name is silently ignored by Spring), these tests would hit 429 and fail.
  test('the IP-wide failed-login limit is raised: 45 failures for 45 different accounts are all plain 401s', async () => {
    const anon = await Api.anonymous();
    const statuses = new Set<number>();
    for (let i = 0; i < 45; i++) statuses.add((await anon.send('POST', '/api/auth/login', { email: uniqueEmail('ipwide'), password: `Wrong-${uid()}-1!` })).status);
    expect(Array.from(statuses)).toEqual([401]);
    await anon.dispose();
  });

  test('the registration-per-IP limit is raised: 12 registrations in a row are all accepted', async () => {
    const anon = await Api.anonymous();
    const statuses = new Set<number>();
    for (let i = 0; i < 12; i++) statuses.add((await anon.send('POST', '/api/auth/register', { email: uniqueEmail('regburst'), password: randomPassword(), name: 'Burst' })).status);
    expect(Array.from(statuses)).toEqual([202]);
    await anon.dispose();
  });
  // ...while the per-account limit keeps its production value: the first describe block proves the sixth attempt is a 429.
});

test.describe('registration', () => {
  test('a new and an already registered address get the same answer, and an existing account is left untouched', async () => {
    const anon = await Api.anonymous();
    const fresh = uniqueEmail('reg'), pw = randomPassword();
    const a = await anon.send('POST', '/api/auth/register', { email: fresh, password: pw, name: 'Reg Tester' });
    const attackerPw = randomPassword();
    const b = await anon.send('POST', '/api/auth/register', { email: employee().email, password: attackerPw, name: 'Mallory' });
    expect(a.status).toBe(202);
    expect(b.status).toBe(202);
    expect(a.body).toEqual({ status: 'ACCEPTED' });
    expect(b.body).toEqual(a.body);                                   // no duplicate signal, no token
    expectNo(JSON.stringify([a.body, b.body]), /token|eyJ/, 'registration answers must carry no token');
    // the existing account kept its password; the registered one works
    expect((await anon.send('POST', '/api/auth/login', { email: employee().email, password: attackerPw })).status).toBe(401);
    expect((await anon.send('POST', '/api/auth/login', { email: employee().email, password: employee().password })).status).toBe(200);
    const login = await anon.send('POST', '/api/auth/login', { email: fresh, password: pw });
    expect(login.status).toBe(200);
    expect(login.body.user.role).not.toBe('ADMIN');
    await anon.dispose();
  });

  test('public registration can never choose a privileged role', async () => {
    const anon = await Api.anonymous();
    const email = uniqueEmail('escalate'), pw = randomPassword();
    const r = await anon.send('POST', '/api/auth/register', { email, password: pw, name: 'Escalate', role: 'ADMIN' });
    expect(r.status).toBe(202);
    const login = await anon.send('POST', '/api/auth/login', { email, password: pw });
    expect(login.status).toBe(200);
    expect(login.body.user.role).not.toBe('ADMIN');
    expect((await (async () => { const s = await Api.login(email, pw); const x = await s.get('/api/admin/users'); await s.dispose(); return x; })()).status).toBe(403);
    await anon.dispose();
  });

  test('registration validates its input with a 400 and no account', async () => {
    const anon = await Api.anonymous();
    const email = uniqueEmail('badreg');
    for (const body of [{ email, password: 'short', name: 'X' }, { email: 'not-an-email', password: randomPassword(), name: 'X' }, { email, password: randomPassword(), name: '' }]) {
      const r = await anon.send('POST', '/api/auth/register', body);
      expect(r.status).toBe(400);
    }
    expect((await anon.send('POST', '/api/auth/login', { email, password: 'short' })).status).toBe(401);
    await anon.dispose();
  });
});
