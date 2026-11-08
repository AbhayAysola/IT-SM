import { test, newUser } from './fixtures';
import { expectNoHorizontalOverflow, signIn } from './helpers';
import { DS_URL } from './fixtures';

test.describe('no horizontal page scroll at phone width (390px)', () => {
  test.use({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });

  test('sign-in screen', async ({ page }) => {
    await page.goto('/');
    await expectNoHorizontalOverflow(page);
  });

  test('sign-in screen, register mode', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Register', exact: true }).click();
    await expectNoHorizontalOverflow(page);
  });

  test('dashboard', async ({ page, api }) => {
    await signIn(page, api, newUser('resp-dash'));
    await expectNoHorizontalOverflow(page);
  });

  test('design-system preview', async ({ page }) => {
    await page.goto(`${DS_URL}/design-system`);
    await page.getByRole('heading', { name: 'IT-SM design system' }).waitFor();
    await expectNoHorizontalOverflow(page);
  });
});

test.describe('desktop 1280px', () => {
  test('design-system preview does not overflow', async ({ page }) => {
    await page.goto(`${DS_URL}/design-system`);
    await page.getByRole('heading', { name: 'IT-SM design system' }).waitFor();
    await expectNoHorizontalOverflow(page);
  });
});
