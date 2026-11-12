import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { expect, type ApiHelper, type TestUser } from './fixtures';

/** WCAG 2.2 AA axe scan. Returns human-readable violations; assert with `expect(...).toEqual([])`. */
export async function axeViolations(page: Page, opts: { skip?: string[] } = {}): Promise<string[]> {
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .disableRules(opts.skip ?? [])
    .analyze();
  return result.violations.map(
    (v) => `${v.id} [${v.impact}] x${v.nodes.length}: ${v.nodes.slice(0, 3).map((n) => n.target.join(' ')).join(' | ')}`
  );
}

/** Wait for finite CSS animations (overlay fade-ins) to end so contrast is measured at full opacity. */
export const settle = (page: Page) =>
  page.waitForFunction(() =>
    document.getAnimations()
      .filter((a) => (a.effect as KeyframeEffect | null)?.getComputedTiming().iterations !== Infinity)
      .every((a) => a.playState !== 'running')
  );

/** Press Tab `count` times; every stop must show a visible focus indicator (ring or outline). */
export async function tabStops(page: Page, count: number) {
  const stops: { name: string; visible: boolean }[] = [];
  for (let i = 0; i < count; i++) {
    await page.keyboard.press('Tab');
    const stop = await page.evaluate(() => {
      const el = document.activeElement;
      if (!el || el === document.body) return null;
      const cs = getComputedStyle(el);
      const ring = cs.boxShadow !== 'none' && /rgb/.test(cs.boxShadow);
      const outline = cs.outlineStyle !== 'none' && parseFloat(cs.outlineWidth) > 0;
      return { name: (el.getAttribute('aria-label') || el.textContent || el.id || el.tagName).trim().slice(0, 40), visible: ring || outline };
    });
    if (stop) stops.push(stop);
  }
  return stops;
}

export async function expectNoHorizontalOverflow(page: Page) {
  const m = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, view: window.innerWidth }));
  expect(m.scroll, `scrollWidth ${m.scroll} exceeds viewport ${m.view}`).toBeLessThanOrEqual(m.view);
}

/** Signs in through the real form. Seeds the account first. Leaves the page on /dashboard with the profile loaded. */
export async function signIn(page: Page, api: ApiHelper, user: TestUser) {
  await api.seed(user);
  await page.goto('/');
  await page.getByLabel('Email address').fill(user.email);
  await page.getByLabel('Password').fill(user.password);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL('**/dashboard');
  await page.locator('dd').first().waitFor();
}

/**
 * Collects uncaught exceptions and console errors. The browser logs "Failed to load resource ... 401" for every anonymous session
 * check (`GET /api/users/me` is how a client learns whether its HttpOnly cookie is valid), so that one expected line is ignored; every
 * other console error, including any other failed resource, is still collected.
 */
export function trackErrors(page: Page) {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => {
    if (m.type() === 'error' && !/Failed to load resource: the server responded with a status of 401/.test(m.text())) {
      errors.push(`console: ${m.text().slice(0, 160)}`);
    }
  });
  return errors;
}

/** The in-form alert (Next.js also renders an empty role=alert route announcer, so scope to <main>). */
export const formAlert = (page: Page) => page.locator('main [role=alert]');
