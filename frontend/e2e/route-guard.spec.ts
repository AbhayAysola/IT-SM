import { test, expect } from './fixtures';
import { trackErrors } from './helpers';

test.describe('route guard', () => {
  test('unauthenticated /dashboard redirects to /?redirect=%2Fdashboard', async ({ page }) => {
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);
    await expect(page.getByRole('heading', { name: 'IT-SM' })).toBeVisible();
  });

  test('a blocked visit makes no API call (regression: dashboard used to call getMe() outside the guard)', async ({ page, api }) => {
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);
    await page.waitForLoadState('networkidle');
    expect(api.calls, `unexpected API calls: ${api.calls.join(', ')}`).toEqual([]);
  });

  test('a blocked visit produces no console errors or uncaught exceptions', async ({ page }) => {
    const errors = trackErrors(page);
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);
    await page.waitForLoadState('networkidle');
    expect(errors).toEqual([]);
  });
});
