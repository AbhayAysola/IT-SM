import type { Incident, IncidentState, Priority, SlaClockView, SlaStatus, SlaSummary } from './types';

/**
 * SLA display rules and the mock service's SLA. The real rules are P4's backend (`sla/SlaEvaluator.kt`, documented in
 * docs/architecture/p4.md section 2); the live service shows what it returns. Pure, so it is tested without pages.
 */

/** Words for every status: the colour is never the only signal (PRODUCT.md principle 1). */
export const SLA_LABEL: Record<SlaStatus, string> = {
  PENDING: 'Starting soon',
  NO_POLICY: 'No SLA policy',
  RUNNING: 'On track',
  WARNING: 'At risk',
  PAUSED: 'Paused',
  MET: 'Met',
  BREACHED: 'Breached',
};

export const SLA_VARIANT: Record<SlaStatus, 'default' | 'secondary' | 'outline' | 'success' | 'warning' | 'destructive'> = {
  PENDING: 'outline', NO_POLICY: 'outline', RUNNING: 'secondary', WARNING: 'warning', PAUSED: 'outline', MET: 'success', BREACHED: 'destructive',
};

/** "in 1 h 5 min", "12 min ago", "now". Minutes are rounded down; days take over from 48 hours. */
export function relativeTo(iso: string, now: number): string {
  const diff = Date.parse(iso) - now;
  const minutes = Math.floor(Math.abs(diff) / 60_000);
  if (minutes === 0) return 'now';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  const text = h >= 48 ? `${Math.floor(h / 24)} days` : h > 0 ? (m > 0 ? `${h} h ${m} min` : `${h} h`) : `${m} min`;
  return diff > 0 ? `in ${text}` : `${text} ago`;
}

/** One line describing a clock, e.g. "Due 13 Nov, 10:05 UTC (in 23 min)". */
export function clockLine(c: SlaClockView, now: number, when: (iso: string) => string): string {
  if (c.status === 'BREACHED' && c.breachedAt) return `Deadline passed ${when(c.breachedAt)}`;
  if (c.status === 'MET') return c.metAt ? `Met ${when(c.metAt)}` : 'Met';
  if (c.status === 'PAUSED') return 'Paused while waiting on the reporter';
  if (c.dueAt) return `Due ${when(c.dueAt)} (${relativeTo(c.dueAt, now)})`;
  return 'No target';
}

/** The seeded global policies (backend V13), used only by the mock service. */
const MOCK_TARGETS: Record<Priority, { response: number; resolution: number }> = {
  P1: { response: 5, resolution: 30 }, P2: { response: 15, resolution: 120 }, P3: { response: 60, resolution: 480 }, P4: { response: 240, resolution: 1440 },
};
const MOCK_WARNING_PCT = 80;
const NOT_RESPONDED: IncidentState[] = ['NEW', 'CLASSIFIED', 'ASSIGNED'];

function mockClock(createdAt: number, minutes: number, now: number, stoppedAt: number | null, paused: boolean, metUnknown = false): SlaClockView {
  const due = createdAt + minutes * 60_000;
  const dueAt = new Date(due).toISOString();
  const base = { targetMinutes: minutes, dueAt, metAt: null, breachedAt: null };
  if (stoppedAt !== null) {
    return stoppedAt > due
      ? { ...base, status: 'BREACHED', breachedAt: dueAt }
      : { ...base, status: 'MET', metAt: metUnknown ? null : new Date(stoppedAt).toISOString() };
  }
  if (paused) return { ...base, status: 'PAUSED' };
  if (now >= due) return { ...base, status: 'BREACHED', breachedAt: dueAt };
  if (now >= createdAt + (minutes * 60_000 * MOCK_WARNING_PCT) / 100) return { ...base, status: 'WARNING' };
  return { ...base, status: 'RUNNING' };
}

/**
 * An approximate SLA for the mock world: the global targets, no pauses history, and the response counted as met (at an
 * unknown time) once the incident is past ASSIGNED, because the mock does not record the first acknowledgement.
 */
export function mockSla(i: Incident, now: number): SlaSummary {
  const t = MOCK_TARGETS[i.priority];
  const created = Date.parse(i.createdAt);
  const responded = !NOT_RESPONDED.includes(i.state);
  const response = mockClock(created, t.response, now, responded ? created : null, false, true);
  const resolution = mockClock(created, t.resolution, now, i.resolvedAt ? Date.parse(i.resolvedAt) : null, i.state === 'PENDING_INFO');
  return { status: overallSla(response.status, resolution.status), response, resolution, pausedSince: i.state === 'PENDING_INFO' ? i.updatedAt : null, evaluatedAt: null };
}

/** The most serious of the two clocks, in the backend's order. */
export function overallSla(response: SlaStatus, resolution: SlaStatus): SlaStatus {
  const order: SlaStatus[] = ['NO_POLICY', 'BREACHED', 'WARNING', 'PAUSED', 'RUNNING', 'MET'];
  return order.find((s) => s === response || s === resolution) ?? 'MET';
}
