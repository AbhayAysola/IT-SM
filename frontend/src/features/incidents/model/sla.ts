import type { EscalationView, Incident, IncidentState, Priority, RowSla, SlaClockView, SlaRiskView, SlaStatus, SlaSummary } from './types';

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

/**
 * Whether a predicted miss is still worth showing: only while the resolution clock is running or warned about. Once it is
 * met, breached or paused, the clock's own status says more.
 */
export function showRisk(sla: SlaSummary): SlaRiskView | null {
  const status = sla.resolution?.status;
  return sla.risk && (status === 'RUNNING' || status === 'WARNING') ? sla.risk : null;
}

/** "Likely to be resolved around 14 Nov, 15:30 UTC, 1 h 30 min after the deadline." */
export function riskLine(r: SlaRiskView, when: (iso: string) => string): string {
  const late = relativeTo(r.predictedFinishAt, Date.parse(r.dueAt)).replace(/^in /, '');
  return `Likely to be resolved around ${when(r.predictedFinishAt)}, ${late} after the deadline.`;
}

/** Why: "Based on 7 similar incidents (same category and priority). The assignee has twice their usual workload." */
export function riskReason(r: SlaRiskView): string {
  const kind = r.basis === 'CATEGORY' ? 'same category and priority' : 'same priority';
  const base = `Based on ${r.sampleSize} similar resolved incidents (${kind}).`;
  if (r.loadFactor <= 1) return base;
  const load = r.loadFactor >= 1.95 && r.loadFactor <= 2.05 ? 'twice' : `${r.loadFactor.toFixed(1)} times`;
  return `${base} The assignee has ${load} their usual workload.`;
}

/**
 * The "Resolution due" cell of the incident list: words first, so the badge colour is never the only signal.
 * Breached shows how long ago; a running clock shows how long is left, flagged when warned or predicted to miss.
 */
export function listDeadline(sla: RowSla | null, now: number): { text: string; badge: { label: string; variant: 'warning' | 'destructive' } | null } {
  if (!sla) return { text: 'Not tracked yet', badge: null };
  switch (sla.resolutionStatus) {
    case 'NO_POLICY': return { text: 'No SLA policy', badge: null };
    case 'MET': return { text: 'Met', badge: null };
    case 'PAUSED': return { text: 'Paused', badge: null };
    case 'BREACHED': return { text: sla.resolutionDueAt ? `Due ${relativeTo(sla.resolutionDueAt, now)}` : 'Deadline passed', badge: { label: 'Breached', variant: 'destructive' } };
    default: {
      const text = sla.resolutionDueAt ? `Due ${relativeTo(sla.resolutionDueAt, now)}` : 'No deadline';
      if (sla.predictedMiss) return { text, badge: { label: 'Likely to miss', variant: 'warning' } };
      if (sla.resolutionStatus === 'WARNING') return { text, badge: { label: 'At risk', variant: 'warning' } };
      return { text, badge: null };
    }
  }
}

/** The mock list's SLA facts, from the same approximate clocks as [mockSla]. The mock never predicts a miss. */
export function mockRowSla(i: Incident, now: number): RowSla | null {
  if (i.state === 'NEW') return null;
  const s = mockSla(i, now);
  const finished = i.state === 'VERIFIED' || i.state === 'CLOSED';
  const status = s.status === 'PENDING' ? 'RUNNING' : s.status;
  const resolution = s.resolution?.status ?? 'NO_POLICY';
  return { status, resolutionStatus: resolution === 'PENDING' ? 'RUNNING' : resolution, resolutionDueAt: finished ? null : s.resolution?.dueAt ?? null, predictedMiss: false };
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
  return { status: overallSla(response.status, resolution.status), response, resolution, pausedSince: i.state === 'PENDING_INFO' ? i.updatedAt : null, evaluatedAt: null, risk: null };
}

/** The most serious of the two clocks, in the backend's order. */
export function overallSla(response: SlaStatus, resolution: SlaStatus): SlaStatus {
  const order: SlaStatus[] = ['NO_POLICY', 'BREACHED', 'WARNING', 'PAUSED', 'RUNNING', 'MET'];
  return order.find((s) => s === response || s === resolution) ?? 'MET';
}

const TIER_LABEL: Record<EscalationView['tier'], string> = { TEAM_LEAD: 'team lead', NEXT_LEVEL: 'next manager', ADMINS: 'administrators' };

/** "sent to Priya (team lead)", or who should have received it when nobody qualified. */
export function escalationAudience(e: EscalationView): string {
  if (e.targets.length === 0) return `no ${TIER_LABEL[e.tier]} to send it to`;
  if (e.tier === 'ADMINS') return `sent to the administrators (${e.targets.join(', ')})`;
  return `sent to ${e.targets.join(', ')} (${TIER_LABEL[e.tier]})`;
}

// ---- the SLA queue (dashboard, staff) -----------------------------------------------------------------------------

/** One open incident in the caller's SLA queue (GET /api/sla/incidents): breached first, then nearest resolution deadline. */
export interface SlaQueueItem {
  incidentId: string;
  title: string;
  priority: Priority;
  sla: RowSla;
}

/** How many rows the dashboard asks for. */
export const SLA_QUEUE_SIZE = 8;

/** Breached first, then the nearest resolution deadline, then the id: the backend's order, used by the mock. */
export function queueOrder(a: SlaQueueItem, b: SlaQueueItem): number {
  const breached = Number(b.sla.status === 'BREACHED') - Number(a.sla.status === 'BREACHED');
  if (breached) return breached;
  const due = (i: SlaQueueItem) => (i.sla.resolutionDueAt ? Date.parse(i.sla.resolutionDueAt) : Number.POSITIVE_INFINITY);
  return due(a) - due(b) || a.incidentId.localeCompare(b.incidentId);
}
