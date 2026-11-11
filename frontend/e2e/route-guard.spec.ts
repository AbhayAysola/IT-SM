import { test, expect } from './fixtures';
import { trackErrors } from './helpers';

test.describe('route guard', () => {
  test('unauthenticated /dashboard redirects to /?redirect=%2Fdashboard', async ({ page }) => {
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);
    await expect(page.getByRole('heading', { name: 'IT-SM' })).toBeVisible();
  });

  test('a blocked visit only asks the backend who the visitor is, once, and calls nothing else', async ({ page, api }) => {
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);
    await page.waitForLoadState('networkidle');
    // The HttpOnly cookie is invisible to the client, so the guard's one /api/users/me (answered 401) is the way to know.
    expect(api.calls, `unexpected API calls: ${api.calls.join(', ')}`).toEqual(['/api/users/me']);
  });

  test('a blocked visit produces no console errors or uncaught exceptions', async ({ page }) => {
    const errors = trackErrors(page);
    await page.goto('/dashboard');
    await page.waitForURL(/\/\?redirect=%2Fdashboard/);
    await page.waitForLoadState('networkidle');
    expect(errors).toEqual([]);
  });
});
