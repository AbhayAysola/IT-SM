import { defineConfig, devices } from '@playwright/test';

/**
 * Browser regression tests. See e2e/README.md.
 *
 * Two production servers are started from the SAME build (run `next build` first; `npm run test:e2e` does):
 *   :3000  default production server       -> /design-system must be a 404
 *   :3002  ENABLE_DESIGN_SYSTEM=true       -> /design-system is served
 */
const WEB_PORT = Number(process.env.E2E_WEB_PORT ?? 3000);
const DS_PORT = Number(process.env.E2E_DS_PORT ?? 3002);

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  expect: { timeout: 7_000 },
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // Deterministic overlays/animations, and exercises the prefers-reduced-motion path in globals.css.
    reducedMotion: 'reduce',
    // Unset = Playwright's bundled Chromium (`npx playwright install chromium`).
    // PW_CHANNEL=chrome uses a locally installed Google Chrome instead.
    channel: process.env.PW_CHANNEL || undefined,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } } }],
  webServer: [
    {
      command: `npx next start -p ${WEB_PORT}`,
      url: `http://localhost:${WEB_PORT}`,
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
    {
      command: `npx next start -p ${DS_PORT}`,
      url: `http://localhost:${DS_PORT}`,
      env: { ENABLE_DESIGN_SYSTEM: 'true' },
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
  ],
});
