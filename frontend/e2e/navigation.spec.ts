import { test, expect, newUser } from './fixtures';
import { signIn } from './helpers';

test.describe('navigation, desktop', () => {
  test('Dashboard link is the current page, and the account menu is operable by keyboard', async ({ page, api }) => {
    await signIn(page, api, newUser('nav'));
    await expect(page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');

    const trigger = page.getByRole('button', { name: /Account menu/ });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const menu = page.getByRole('menu');
    await expect(menu).toBeVisible();
    await expect(menu.getByText('Signed in as')).toBeVisible();
    await expect(menu.getByRole('menuitem', { name: 'Sign out' })).toBeVisible();
  });

  test('an open menu keeps focus inside it, and Escape closes it and returns focus to the trigger', async ({ page, api }) => {
    await signIn(page, api, newUser('menu-focus'));
    await page.getByRole('button', { name: /Account menu/ }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('menu')).toBeVisible();

    let escaped = 0;
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press('Tab');
      if (!(await page.evaluate(() => !!document.activeElement?.closest('[role=menu]')))) escaped++;
    }
    expect(escaped, 'Tab moved focus out of the open menu').toBe(0);

    await page.keyboard.press('Escape');
    await expect(page.getByRole('menu')).toBeHidden();
    expect(await page.evaluate(() => /Account menu/.test(document.activeElement?.textContent ?? ''))).toBe(true);
  });
});

test.describe('navigation, mobile', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  test('primary links collapse into the account menu', async ({ page, api }) => {
    await signIn(page, api, newUser('mobile-nav'));
    await expect(page.getByRole('navigation', { name: 'Primary' })).toBeHidden();
    await page.getByRole('button', { name: /Account menu/ }).click();
    await expect(page.getByRole('menuitem', { name: 'Dashboard' })).toBeVisible();
  });

  test('header controls meet the 24x24 px minimum target size (WCAG 2.2 AA 2.5.8)', async ({ page, api }) => {
    await signIn(page, api, newUser('targets'));
    const small = await page.evaluate(() =>
      Array.from(document.querySelectorAll('header button, header a'))
        .filter((e) => e.getClientRects().length > 0)
        .map((e) => { const r = e.getBoundingClientRect(); return { name: (e.getAttribute('aria-label') || e.textContent || '').trim().slice(0, 24), w: r.width, h: r.height }; })
        .filter((t) => t.w < 24 || t.h < 24)
    );
    expect(small).toEqual([]);
  });
});
