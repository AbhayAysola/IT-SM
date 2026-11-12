import { test, expect } from './fixtures';

test('the app declares a favicon that loads, and no request fails with 404', async ({ page, baseURL }) => {
  const failed: string[] = [];
  // The anonymous session check (GET /api/users/me -> 401) is expected; anything else that fails is not.
  page.on('response', (r) => { if (r.status() >= 400 && !r.url().endsWith('/api/users/me')) failed.push(`${r.status()} ${r.url()}`); });

  await page.goto('/');
  await page.waitForLoadState('networkidle');

  const link = page.locator('link[rel~=icon]');
  await expect(link, 'no <link rel="icon"> found').toHaveCount(1);
  const href = await link.getAttribute('href');
  const icon = await page.request.get(new URL(href!, baseURL).toString());
  expect(icon.status()).toBe(200);
  expect(icon.headers()['content-type']).toMatch(/svg/);
  expect(failed).toEqual([]);
});
