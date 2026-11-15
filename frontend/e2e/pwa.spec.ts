/**
 * P4's PWA: the manifest and icons that make IT-SM installable, the service worker with its offline page, the desktop
 * notifications opt-in, and the real-time stream client (with a stand-in EventSource, no backend).
 */
import { test, expect, newUser } from './fixtures';
import { axeViolations, signIn } from './helpers';
import { shouldShowDesktop } from '../src/features/notifications/desktop';
import { liveNotificationService, type LiveNotification } from '../src/features/notifications/notification-service';

test.describe('installable app', () => {
  test('the manifest describes an installable app, and its icons are real PNGs of the right size', async ({ request }) => {
    const res = await request.get('/manifest.webmanifest');
    expect(res.ok()).toBe(true);
    const m = await res.json();
    expect(m).toMatchObject({ short_name: 'IT-SM', start_url: '/dashboard', display: 'standalone', theme_color: '#0f172a' });
    const sizes = m.icons.map((i: { sizes: string; purpose: string }) => `${i.sizes} ${i.purpose}`);
    expect(sizes).toEqual(['192x192 any', '512x512 any', '512x512 maskable']);
    for (const icon of m.icons as { src: string }[]) {
      const png = await request.get(icon.src);
      expect(png.headers()['content-type']).toBe('image/png');
      const b = await png.body();
      expect(b.subarray(1, 4).toString()).toBe('PNG');
    }
  });

  test('every page links the manifest, the theme colour and the Apple icon', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('link[rel="manifest"]')).toHaveAttribute('href', '/manifest.webmanifest');
    await expect(page.locator('meta[name="theme-color"]')).toHaveAttribute('content', '#0f172a');
    const apple = await page.locator('link[rel="apple-touch-icon"]').getAttribute('href');
    expect(apple).toMatch(/^\/apple-icon\.png/);
    expect((await page.request.get(apple!)).headers()['content-type']).toBe('image/png');
  });

  test('the service worker installs, and a page load without a connection shows the offline page', async ({ page, context }) => {
    await page.goto('/');
    const scope = await page.evaluate(async () => (await navigator.serviceWorker.ready).scope);
    expect(new URL(scope).pathname).toBe('/');
    await page.reload(); // now controlled by the worker
    await expect.poll(() => page.evaluate(() => !!navigator.serviceWorker.controller)).toBe(true);
    await context.setOffline(true);
    await page.goto('/incidents').catch(() => undefined);
    await expect(page.getByRole('heading', { name: 'You are offline' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
    await context.setOffline(false);
  });
});

test.describe('desktop notifications', () => {
  test('are off until the person turns them on, and say so in words', async ({ page, api, context }) => {
    await signIn(page, api, newUser('desktop'));
    await page.goto('/notifications');
    const box = page.getByTestId('desktop-notifications');
    await expect(box).toContainText('Get a desktop notification when something new arrives');
    expect(await axeViolations(page)).toEqual([]);
    await context.grantPermissions(['notifications']);
    await page.reload();
    await expect(box).toContainText('Desktop notifications are on');
    await expect(box.getByRole('button')).toHaveCount(0);
  });

  test('are shown only when allowed and the app is in the background', () => {
    expect(shouldShowDesktop('granted', 'hidden')).toBe(true);
    expect(shouldShowDesktop('granted', 'visible')).toBe(false); // the bell shows it already
    expect(shouldShowDesktop('default', 'hidden')).toBe(false);
    expect(shouldShowDesktop('denied', 'hidden')).toBe(false);
  });
});

test.describe('real-time stream client', () => {
  type Listener = (e: { data: string }) => void;
  class FakeEventSource {
    static last: FakeEventSource | null = null;
    listeners: Record<string, Listener> = {};
    onerror: (() => void) | null = null;
    closed = false;
    constructor(public url: string, public init?: { withCredentials?: boolean }) { FakeEventSource.last = this; }
    addEventListener(name: string, fn: Listener) { this.listeners[name] = fn; }
    close() { this.closed = true; }
  }

  test('connects with the session cookie, reports the count and each notification, ignores garbage, and closes', () => {
    (globalThis as unknown as { EventSource: unknown }).EventSource = FakeEventSource;
    const seen: { ready: number[]; notes: LiveNotification[]; drops: number } = { ready: [], notes: [], drops: 0 };
    const stop = liveNotificationService.stream({
      onReady: (n) => seen.ready.push(n), onNotification: (n) => seen.notes.push(n), onDisconnect: () => { seen.drops++; },
    });
    const es = FakeEventSource.last!;
    expect(es.url).toMatch(/\/api\/notifications\/stream$/);
    expect(es.init).toEqual({ withCredentials: true });

    es.listeners.ready({ data: '{"unreadCount":4}' });
    const note: LiveNotification = { type: 'ESCALATED', incidentId: 'INC1002', priority: 'P1', title: 'INC1002 was escalated to you', body: 'x', unreadCount: 5 };
    es.listeners.notification({ data: JSON.stringify(note) });
    es.listeners.notification({ data: 'not json' });
    es.onerror!();
    expect(seen).toEqual({ ready: [4], notes: [note], drops: 1 });
    stop();
    expect(es.closed).toBe(true);
    delete (globalThis as unknown as { EventSource?: unknown }).EventSource;
  });
});
