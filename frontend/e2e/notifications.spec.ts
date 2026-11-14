/** P4's notification bell and inbox, against the mock inbox (the backend's NotificationIntegrationTest proves the real rules). */
import { test, expect, newUser } from './fixtures';
import { axeViolations, expectNoHorizontalOverflow, signIn, trackErrors } from './helpers';
import { notificationHref, toNotification } from '../src/features/notifications/notification-service';
import { relativeTime } from '../src/features/notifications/notification-time';

const bell = (page: import('@playwright/test').Page) => page.getByTestId('notification-bell');

test.describe('notifications', () => {
  test('the bell says how many are unread, lists the latest and clears them', async ({ page, api }) => {
    const errors = trackErrors(page);
    await signIn(page, api, newUser('ntf-bell', 'EMPLOYEE'));
    await page.goto('/incidents');
    await expect(bell(page)).toHaveAccessibleName('Notifications, 2 unread');
    await bell(page).click();
    const menu = page.getByRole('menu');
    await expect(menu.getByRole('menuitem').filter({ hasText: 'INC1002: resolution SLA at risk' })).toBeVisible();
    await expect(menu.getByRole('menuitem').filter({ hasText: 'A technician is working on INC1001' })).toBeVisible();
    await menu.getByRole('button', { name: 'Mark all as read' }).click();
    await page.keyboard.press('Escape');
    await expect(bell(page)).toHaveAccessibleName('Notifications, none unread');
    expect(errors).toEqual([]);
  });

  test('opening a notification goes to its incident and marks it read', async ({ page, api }) => {
    await signIn(page, api, newUser('ntf-open', 'EMPLOYEE'));
    await page.goto('/incidents');
    await bell(page).click();
    await page.getByRole('menuitem').filter({ hasText: 'INC1002: resolution SLA at risk' }).click();
    await expect(page).toHaveURL(/\/incidents\/INC1002$/);
    await expect(bell(page)).toHaveAccessibleName('Notifications, 1 unread');
  });

  test('the inbox lists everything, filters unread, and marks read in words', async ({ page, api }) => {
    const errors = trackErrors(page);
    await signIn(page, api, newUser('ntf-inbox', 'TECHNICIAN'));
    await page.goto('/notifications');
    const list = page.getByRole('list', { name: 'Notifications' });
    await expect(list.getByRole('listitem')).toHaveCount(3);
    await expect(list.getByText('Unread', { exact: true })).toHaveCount(2);

    await page.getByRole('button', { name: 'Unread', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Unread', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(list.getByRole('listitem')).toHaveCount(2);
    await list.getByRole('listitem').first().getByRole('button', { name: 'Mark as read' }).click();
    await expect(list.getByRole('listitem')).toHaveCount(1);
    expect(await axeViolations(page)).toEqual([]);

    await page.getByRole('button', { name: 'Mark all as read' }).click();
    await expect(page.getByText('Nothing unread')).toBeVisible();
    await expect(bell(page)).toHaveAccessibleName('Notifications, none unread');
    expect(errors).toEqual([]);
  });

  test('the inbox works at phone width', async ({ page, api }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signIn(page, api, newUser('ntf-phone', 'EMPLOYEE'));
    await page.goto('/notifications');
    await expect(page.getByRole('list', { name: 'Notifications' }).getByRole('listitem')).toHaveCount(3);
    await expectNoHorizontalOverflow(page);
  });

  test('signed-out visitors are sent to sign-in', async ({ page }) => {
    await page.goto('/notifications');
    await page.waitForURL((u) => u.pathname === '/' && u.searchParams.get('redirect') === '/notifications');
  });
});

test.describe('notification rules', () => {
  test('the live mapper keeps what the inbox shows', () => {
    const n = toNotification({ id: 4, type: 'ESCALATED', incidentId: 'INC7', priority: 'P1', title: 't', body: 'b', createdAt: '2026-11-14T10:00:00Z', readAt: null, channels: ['IN_APP', 'EMAIL', 'SMS'] });
    expect(n).toEqual({ id: 4, type: 'ESCALATED', incidentId: 'INC7', priority: 'P1', title: 't', body: 'b', createdAt: '2026-11-14T10:00:00Z', readAt: null });
    expect(notificationHref(n)).toBe('/incidents/INC7');
    expect(notificationHref({ ...n, incidentId: null })).toBeNull();
  });

  test('times read naturally', () => {
    const now = Date.parse('2026-11-14T10:00:00Z');
    expect(relativeTime('2026-11-14T09:59:40Z', now)).toBe('just now');
    expect(relativeTime('2026-11-14T09:55:00Z', now)).toBe('5 min ago');
    expect(relativeTime('2026-11-14T07:00:00Z', now)).toBe('3 h ago');
    expect(relativeTime('2026-11-11T10:00:00Z', now)).toBe('3 days ago');
  });
});
