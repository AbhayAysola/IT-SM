/** Formatting for the analytics dashboard. Pure, so it is tested without pages. */

/** "45 min", "2 h 5 min", "3 d 4 h"; an em dash when there is nothing to show. */
export function formatMinutes(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined || !Number.isFinite(minutes)) return '—';
  const m = Math.round(minutes);
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60), rest = m % 60;
  if (h < 48) return rest ? `${h} h ${rest} min` : `${h} h`;
  const d = Math.floor(h / 24), rh = h % 24;
  return rh ? `${d} d ${rh} h` : `${d} d`;
}

/** 1,284 / 12.9K / 4.2M. */
export function compact(n: number): string {
  if (Math.abs(n) < 10_000) return n.toLocaleString('en-GB');
  if (Math.abs(n) < 1_000_000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}K`;
  return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`;
}

export const pct = (v: number | null | undefined) => (v === null || v === undefined ? '—' : `${v.toFixed(1).replace(/\.0$/, '')}%`);

/** A clean axis maximum at or above [max]: 1, 2, 5 × 10^n (never 0, so an empty chart still has a scale). */
export function niceMax(max: number): number {
  if (max <= 0) return 1;
  const p = 10 ** Math.floor(Math.log10(max));
  for (const m of [1, 2, 5, 10]) if (m * p >= max) return m * p;
  return 10 * p;
}

/** "3 Nov" for a YYYY-MM-DD day. */
export const shortDay = (iso: string) => new Date(`${iso}T00:00:00Z`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', timeZone: 'UTC' });

export const TRIGGER_LABEL: Record<string, string> = {
  RESPONSE_BREACHED: 'Response SLA breached',
  RESOLUTION_BREACHED: 'Resolution SLA breached',
  RESOLUTION_OVERDUE: 'Resolution far past its target',
  RESOLUTION_CRITICAL: 'Resolution critically past its target',
  UNASSIGNED: 'Not assigned in time',
};
