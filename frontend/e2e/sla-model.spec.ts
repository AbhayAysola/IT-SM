/** Pure checks of the SLA display rules, the mock SLA and the live mapper (no browser, no backend). */
import { test, expect } from '@playwright/test';
import { clockLine, escalationAudience, mockSla, overallSla, relativeTo, SLA_LABEL } from '../src/features/incidents/model/sla';
import type { Incident, SlaStatus } from '../src/features/incidents/model/types';
import { toEscalation, toSla } from '../src/features/incidents/services/incident-mappers';

const NOW = Date.parse('2026-11-13T10:00:00Z');
const minutes = (m: number) => new Date(NOW + m * 60_000).toISOString();
const when = (iso: string) => iso; // the page formats; the rules only choose the words

const incident = (over: Partial<Incident>): Incident => ({
  id: 'INC1001', title: 't', description: 'd', category: 'Network', service: 'Wi-Fi', priority: 'P3', state: 'CLASSIFIED',
  reporterEmail: 'r@campus.example', reporterName: 'R', assigneeEmail: null, assigneeName: null,
  createdAt: minutes(-10), updatedAt: minutes(-10), resolvedAt: null, ...over,
});

test.describe('SLA display rules', () => {
  test('every status has words, so colour is never the only signal', () => {
    const all: SlaStatus[] = ['PENDING', 'NO_POLICY', 'RUNNING', 'WARNING', 'PAUSED', 'MET', 'BREACHED'];
    for (const s of all) expect(SLA_LABEL[s].length).toBeGreaterThan(0);
    expect(new Set(all.map((s) => SLA_LABEL[s])).size).toBe(all.length);
  });

  test('relative times read naturally in both directions', () => {
    expect(relativeTo(minutes(23), NOW)).toBe('in 23 min');
    expect(relativeTo(minutes(-12), NOW)).toBe('12 min ago');
    expect(relativeTo(minutes(65), NOW)).toBe('in 1 h 5 min');
    expect(relativeTo(minutes(120), NOW)).toBe('in 2 h');
    expect(relativeTo(minutes(3 * 24 * 60), NOW)).toBe('in 3 days');
    expect(relativeTo(minutes(0.5), NOW)).toBe('now');
  });

  test('each clock state is described in plain words', () => {
    const base = { targetMinutes: 60, dueAt: minutes(23), metAt: null, breachedAt: null };
    expect(clockLine({ ...base, status: 'RUNNING' }, NOW, when)).toBe(`Due ${minutes(23)} (in 23 min)`);
    expect(clockLine({ ...base, status: 'MET', metAt: minutes(-5) }, NOW, when)).toBe(`Met ${minutes(-5)}`);
    expect(clockLine({ ...base, status: 'MET' }, NOW, when)).toBe('Met');
    expect(clockLine({ ...base, status: 'BREACHED', breachedAt: minutes(-1) }, NOW, when)).toBe(`Deadline passed ${minutes(-1)}`);
    expect(clockLine({ ...base, status: 'PAUSED' }, NOW, when)).toBe('Paused while waiting on the reporter');
    expect(clockLine({ ...base, dueAt: null, status: 'NO_POLICY' }, NOW, when)).toBe('No target');
  });

  test('the overall status is the most serious clock, in the backend order', () => {
    expect(overallSla('MET', 'BREACHED')).toBe('BREACHED');
    expect(overallSla('BREACHED', 'RUNNING')).toBe('BREACHED');
    expect(overallSla('MET', 'PAUSED')).toBe('PAUSED');
    expect(overallSla('WARNING', 'RUNNING')).toBe('WARNING');
    expect(overallSla('MET', 'MET')).toBe('MET');
  });
});

test.describe('mock SLA', () => {
  test('uses the seeded global targets from when the incident was reported', () => {
    const s = mockSla(incident({ priority: 'P3', createdAt: minutes(-10) }), NOW);
    expect(s.status).toBe('RUNNING');
    expect(s.response).toMatchObject({ status: 'RUNNING', targetMinutes: 60, dueAt: minutes(50) });
    expect(s.resolution).toMatchObject({ status: 'RUNNING', targetMinutes: 480 });
  });

  test('warns at 80 %, breaches at the deadline, and pauses while waiting on the reporter', () => {
    expect(mockSla(incident({ priority: 'P1', createdAt: minutes(-4) }), NOW).response?.status).toBe('WARNING');
    const late = mockSla(incident({ priority: 'P1', createdAt: minutes(-6) }), NOW);
    expect(late.response).toMatchObject({ status: 'BREACHED', breachedAt: minutes(-1) });
    const waiting = mockSla(incident({ state: 'PENDING_INFO', createdAt: minutes(-10), updatedAt: minutes(-2) }), NOW);
    expect(waiting.resolution?.status).toBe('PAUSED');
    expect(waiting.pausedSince).toBe(minutes(-2));
  });

  test('a resolved incident is met in time or breached if late', () => {
    expect(mockSla(incident({ state: 'RESOLVED', createdAt: minutes(-100), resolvedAt: minutes(-50) }), NOW).resolution?.status).toBe('MET');
    expect(mockSla(incident({ state: 'RESOLVED', priority: 'P1', createdAt: minutes(-100), resolvedAt: minutes(-50) }), NOW).resolution?.status).toBe('BREACHED');
  });
});

test.describe('live SLA mapper', () => {
  test('keeps the statuses and instants the backend sends', () => {
    const clock = { status: 'WARNING' as const, targetMinutes: 15, dueAt: minutes(3), metAt: null, breachedAt: null };
    const s = toSla({ status: 'WARNING', response: clock, resolution: null, pausedSince: null, evaluatedAt: minutes(-1) });
    expect(s).toEqual({ status: 'WARNING', response: clock, resolution: null, pausedSince: null, evaluatedAt: minutes(-1) });
    expect(toSla({ status: 'PENDING', response: null, resolution: null, pausedSince: null, evaluatedAt: null }).status).toBe('PENDING');
  });
});

test.describe('escalations', () => {
  test('the live mapper keeps what the page shows and drops ids', () => {
    const e = toEscalation({ trigger: 'RESPONSE_BREACHED', tier: 'TEAM_LEAD', description: 'Response SLA breached', targets: [{ id: 4, name: 'Priya' }], assigneeId: 9, createdAt: minutes(-3) });
    expect(e).toEqual({ description: 'Response SLA breached', tier: 'TEAM_LEAD', targets: ['Priya'], createdAt: minutes(-3) });
  });

  test('who it went to is said in words, including when nobody qualified', () => {
    const base = { description: 'x', createdAt: minutes(0) };
    expect(escalationAudience({ ...base, tier: 'TEAM_LEAD', targets: ['Priya'] })).toBe('sent to Priya (team lead)');
    expect(escalationAudience({ ...base, tier: 'NEXT_LEVEL', targets: ['Omar'] })).toBe('sent to Omar (next manager)');
    expect(escalationAudience({ ...base, tier: 'ADMINS', targets: ['Ada', 'Grace'] })).toBe('sent to the administrators (Ada, Grace)');
    expect(escalationAudience({ ...base, tier: 'TEAM_LEAD', targets: [] })).toBe('no team lead to send it to');
  });
});
