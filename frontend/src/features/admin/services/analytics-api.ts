import type { AnalyticsSummary } from '../types';
import { ServiceError } from '../types';
import { call, isObj } from './http';

export const ANALYTICS_MAX_DAYS = 366;

/** YYYY-MM-DD for a Date, in UTC (the backend's days are UTC). */
export const isoDay = (d: Date) => d.toISOString().slice(0, 10);

/** The last [days] UTC days ending today, inclusive. */
export function presetRange(days: number, today = new Date()): { from: string; to: string } {
  const to = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate()));
  const from = new Date(to.getTime() - (days - 1) * 86_400_000);
  return { from: isoDay(from), to: isoDay(to) };
}

/** Same rules as the backend (AnalyticsService.validate); empty when valid. */
export function validateRange(from: string, to: string): string | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to)) return 'Choose a start and an end date.';
  const days = (Date.parse(to) - Date.parse(from)) / 86_400_000 + 1;
  if (days < 1) return 'The start date must not be after the end date.';
  if (days > ANALYTICS_MAX_DAYS) return `Choose at most ${ANALYTICS_MAX_DAYS} days.`;
  return null;
}

export const analyticsApi = {
  async summary(from: string, to: string): Promise<AnalyticsSummary> {
    const x = await call<unknown>('/api/admin/analytics/summary', { query: { from, to } });
    if (!(isObj(x) && isObj(x.incidents) && isObj(x.sla) && Array.isArray(x.perDay) && Array.isArray(x.technicians))) {
      throw new ServiceError('UNAVAILABLE', 'Something went wrong on our side. Please try again.');
    }
    return x as unknown as AnalyticsSummary;
  },
};
