import { defineConfig, devices } from '@playwright/test';

/**
 * REAL-STACK suite (see e2e-real/README.md). Real Next.js production build, real Spring Boot backend, real PostgreSQL 16.
 * Nothing is mocked or intercepted. The fast fake-API suite (playwright.config.ts, e2e/) is a separate layer and is not touched.
 *
 * Determinism: one worker (the assignment/SLA settings are shared singletons), no retries (a test that needs a retry is flaky,
 * and a flaky test fails the job), every test creates its own uniquely named data.
 *
 * Secret safety: NO trace, video or screenshot. Traces and the HTML report record request headers, bodies and cookies, which here
 * means the session JWT and generated passwords. The only reporters are the console list and a JSON file that
 * scripts/check-real-stack-results.mjs reads (it carries test titles, statuses and error text, never payloads).
 */
const WEB_PORT = Number(process.env.E2E_WEB_PORT ?? 3000);

export default defineConfig({
  testDir: './e2e-real',
  testMatch: '**/*.spec.ts',
  globalSetup: './e2e-real/global-setup.ts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['json', { outputFile: 'real-stack-report/results.json' }]],
  outputDir: 'real-stack-report/artifacts',
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    trace: 'off',
    video: 'off',
    screenshot: 'off',
    reducedMotion: 'reduce',
    channel: process.env.PW_CHANNEL || undefined,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } } }],
  webServer: {
    command: `npx next start -p ${WEB_PORT}`,
    url: `http://localhost:${WEB_PORT}`,
    // Never silently test some other server that happens to be on the port.
    reuseExistingServer: false,
    timeout: 60_000,
  },
});
