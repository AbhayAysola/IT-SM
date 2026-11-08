import { test, expect, DS_URL } from './fixtures';
import { axeViolations, settle, tabStops, trackErrors } from './helpers';

const MODAL_SKIP = { skip: ['aria-hidden-focus'] }; // see accessibility.spec.ts

test.describe('availability (development-only page)', () => {
  test('a default production server returns 404', async ({ page, baseURL }) => {
    const res = await page.goto(`${baseURL}/design-system`);
    expect(res?.status()).toBe(404);
  });

  test('ENABLE_DESIGN_SYSTEM=true serves it, marked noindex', async ({ page }) => {
    const res = await page.goto(`${DS_URL}/design-system`);
    expect(res?.status()).toBe(200);
    await expect(page.getByRole('heading', { name: 'IT-SM design system' })).toBeVisible();
    await expect(page.locator('meta[name=robots]')).toHaveAttribute('content', /noindex/);
  });
});

test.describe('design-system preview', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto(`${DS_URL}/design-system`);
    await page.getByRole('heading', { name: 'IT-SM design system' }).waitFor();
  });

  test('renders every section without errors, and passes axe', async ({ page }) => {
    const errors = trackErrors(page);
    for (const name of ['Colour', 'Typography', 'Button', 'Input, Label, Select', 'Badge', 'Card', 'Table', 'Tabs', 'Dialog, Sheet, Dropdown menu, Tooltip', 'Sidebar', 'Skeleton']) {
      await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
    }
    expect(await axeViolations(page)).toEqual([]);
    expect(errors).toEqual([]);
  });

  test('every Tab stop shows a focus indicator', async ({ page }) => {
    const stops = await tabStops(page, 12);
    expect(stops.length).toBeGreaterThan(8);
    expect(stops.filter((s) => !s.visible).map((s) => s.name)).toEqual([]);
  });

  test('dialog: focus moves in, Escape closes, focus returns to the trigger', async ({ page }) => {
    const trigger = page.getByRole('button', { name: 'Open dialog' });
    await trigger.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();
    expect(await page.evaluate(() => !!document.querySelector('[role=dialog]')?.contains(document.activeElement))).toBe(true);
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(trigger).toBeFocused();
  });

  test('select: opens, passes axe, and picks an option with the keyboard', async ({ page }) => {
    const select = page.getByRole('combobox').first();
    await select.click();
    await expect(page.getByRole('option', { name: 'Network' })).toBeVisible();
    await settle(page);
    expect(await axeViolations(page, MODAL_SKIP)).toEqual([]);
    // Radix opens with the first option focused; ArrowDown moves to the second; Enter selects it.
    await expect(page.getByRole('option', { name: 'Network' })).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('option', { name: 'Database' })).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(select).toContainText('Database');
    await expect(select).toBeFocused();
  });

  test('dropdown menu: opens, passes axe, closes on Escape', async ({ page }) => {
    await page.getByRole('button', { name: 'Actions' }).click();
    await expect(page.getByRole('menu')).toBeVisible();
    await settle(page);
    expect(await axeViolations(page, MODAL_SKIP)).toEqual([]);
    await page.keyboard.press('Escape');
    await expect(page.getByRole('menu')).toBeHidden();
  });

  test('sheet: opens, passes axe, closes on Escape and returns focus', async ({ page }) => {
    const trigger = page.getByRole('button', { name: 'Open sheet' });
    await trigger.click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await settle(page);
    expect(await axeViolations(page)).toEqual([]);
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toBeHidden();
    await expect(trigger).toBeFocused();
  });

  test('tooltip appears on keyboard focus', async ({ page }) => {
    await page.getByRole('button', { name: 'Notifications' }).focus();
    await expect(page.getByRole('tooltip').first()).toBeVisible();
  });

  test('tabs respond to arrow keys', async ({ page }) => {
    const overview = page.getByRole('tab', { name: 'Overview' });
    await overview.focus();
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('tab', { name: 'Activity' })).toHaveAttribute('aria-selected', 'true');
  });

  test('the sidebar specimen shows its footer inside the frame (regression: it was clipped)', async ({ page }) => {
    const footerItem = page.locator('#sidebar').getByRole('button', { name: 'Sign out' });
    await footerItem.scrollIntoViewIfNeeded();
    const frame = await page.locator('#sidebar .overflow-hidden').first().boundingBox();
    const box = await footerItem.boundingBox();
    expect(frame && box && box.y + box.height <= frame.y + frame.height).toBe(true);
  });
});
