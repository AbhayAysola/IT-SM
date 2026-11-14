/** P4's analytics dashboard against the fake admin API (the backend's AnalyticsIntegrationTest proves the real figures). */
import { test, expect } from './fixtures';
import { openAdmin } from './admin-helpers';
import { axeViolations, expectNoHorizontalOverflow, trackErrors } from './helpers';
import { presetRange, validateRange } from '../src/features/admin/services/analytics-api';
import { compact, formatMinutes, niceMax, pct } from '../src/features/admin/analytics/format';

const PATH = '/admin/analytics';
const lastSummary = (api: { admin: { last: (m: string, p: string) => { query: URLSearchParams } | undefined } }) => api.admin.last('GET', '/api/admin/analytics/summary')!.query;

test.describe('admin: analytics', () => {
  test('is in the navigation and leads with the headline numbers, for the last 30 days', async ({ page, api }) => {
    const errors = trackErrors(page);
    await openAdmin(page, api, '/admin');
    await page.getByRole('link', { name: 'Analytics' }).click();
    await expect(page.getByRole('heading', { name: 'Analytics', level: 1 })).toBeVisible();
    await expect(page.getByTestId('kpi-open')).toContainText('12');
    await expect(page.getByTestId('kpi-open')).toContainText('3 not assigned');
    await expect(page.getByTestId('kpi-sla')).toContainText('80%');
    await expect(page.getByTestId('kpi-sla')).toContainText('36 met · 9 breached');
    await expect(page.getByRole('button', { name: 'Last 30 days' })).toHaveAttribute('aria-pressed', 'true');
    const expected = presetRange(30);
    expect([lastSummary(api).get('from'), lastSummary(api).get('to')]).toEqual([expected.from, expected.to]);
    await expect(page.getByText('5 h 12 min')).toBeVisible(); // mean time to resolve, 312 minutes
    await expect(page.getByText('Response SLA breached')).toBeVisible(); // escalations by reason
    expect(await axeViolations(page)).toEqual([]);
    expect(errors).toEqual([]);
  });

  test('a preset or a custom range rescopes everything; a backwards range is refused before any request', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await expect(page.getByTestId('analytics')).toBeVisible();
    await page.getByRole('button', { name: 'Last 7 days' }).click();
    await expect(page.getByRole('button', { name: 'Last 7 days' })).toHaveAttribute('aria-pressed', 'true');
    await expect.poll(() => lastSummary(api).get('from')).toBe(presetRange(7).from);

    const before = api.admin.count('GET', '/api/admin/analytics/summary');
    await page.getByLabel('From', { exact: true }).fill('2026-03-10');
    await page.getByLabel('To', { exact: true }).fill('2026-03-01');
    await page.getByRole('button', { name: 'Apply' }).click();
    await expect(page.getByText('The start date must not be after the end date.')).toBeVisible();
    expect(api.admin.count('GET', '/api/admin/analytics/summary')).toBe(before);

    await page.getByLabel('To', { exact: true }).fill('2026-03-14');
    await page.getByRole('button', { name: 'Apply' }).click();
    await expect.poll(() => [lastSummary(api).get('from'), lastSummary(api).get('to')]).toEqual(['2026-03-10', '2026-03-14']);
    await expect(page.getByRole('button', { name: 'Last 7 days' })).toHaveAttribute('aria-pressed', 'false');
  });

  test('every chart has a table twin, and the daily chart reads each day from the keyboard', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: 'Last 7 days' }).click();
    await expect.poll(() => lastSummary(api).get('from')).toBe(presetRange(7).from);
    const chart = page.getByRole('group', { name: /^Reported:/ });
    await chart.focus();
    await page.keyboard.press('ArrowRight');
    await expect(chart.getByRole('status')).toContainText('reported on');

    await page.getByRole('button', { name: 'Show as tables' }).click();
    await expect(page.getByRole('table', { name: 'Every day' }).locator('tbody tr')).toHaveCount(7);
    await expect(page.getByRole('table', { name: 'Escalations by reason' })).toContainText('Not assigned in time');
    await expect(page.getByRole('table', { name: 'Technicians' })).toContainText('4.5 of 5 (6)');
    await expect(page.getByRole('table', { name: 'Technicians' })).toContainText('4 of 5');
    expect(await axeViolations(page)).toEqual([]);
  });

  test('a failed load offers a retry', async ({ page, api }) => {
    api.admin.failures.push({ match: 'GET /api/admin/analytics/summary', status: 500, code: 'INTERNAL_ERROR', detail: 'boom', times: 1 });
    await openAdmin(page, api, PATH);
    await page.getByRole('button', { name: /try again|retry/i }).click();
    await expect(page.getByTestId('kpi-reported')).toBeVisible();
  });

  test('works at phone width', async ({ page, api }) => {
    await openAdmin(page, api, PATH);
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByTestId('kpi-reported')).toBeVisible();
    await expectNoHorizontalOverflow(page);
  });
});

test.describe('analytics rules', () => {
  test('durations, counts and percentages read naturally', () => {
    expect(formatMinutes(45)).toBe('45 min');
    expect(formatMinutes(125)).toBe('2 h 5 min');
    expect(formatMinutes(120)).toBe('2 h');
    expect(formatMinutes(60 * 75)).toBe('3 d 3 h');
    expect(formatMinutes(null)).toBe('—');
    expect(compact(1284)).toBe('1,284');
    expect(compact(12_900)).toBe('12.9K');
    expect(compact(4_200_000)).toBe('4.2M');
    expect(pct(80)).toBe('80%');
    expect(pct(33.3)).toBe('33.3%');
    expect(pct(null)).toBe('—');
  });

  test('axes end on a clean number, and an empty chart still has one', () => {
    expect(niceMax(0)).toBe(1);
    expect(niceMax(3)).toBe(5);
    expect(niceMax(7)).toBe(10);
    expect(niceMax(130)).toBe(200);
  });

  test('ranges are UTC days, inclusive, at most 366, never backwards', () => {
    expect(presetRange(7, new Date('2026-11-14T23:30:00Z'))).toEqual({ from: '2026-11-08', to: '2026-11-14' });
    expect(validateRange('2026-01-01', '2026-01-01')).toBeNull();
    expect(validateRange('2026-01-01', '2026-12-31')).toBeNull();
    expect(validateRange('2025-01-01', '2026-01-02')).toBe('Choose at most 366 days.');
    expect(validateRange('2026-01-02', '2026-01-01')).toBe('The start date must not be after the end date.');
    expect(validateRange('', '2026-01-01')).toBe('Choose a start and an end date.');
  });
});
