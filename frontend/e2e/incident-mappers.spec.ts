/** Pure checks of the live-API mappers (no browser, no backend): what the pages get from the backend's JSON. */
import { test, expect } from '@playwright/test';
import { needsAttention } from '../src/features/incidents/services/attention';
import { TABS } from '../src/features/incidents/services/tabs';
import type { Actor } from '../src/features/incidents/model/types';
import {
  liveEmail, liveId, toIncident, toIncidentFromSummary, toIncidentError, toRecommendation, toSimilar, toTimelineEntry, who, type ApiIncident, type Me,
} from '../src/features/incidents/services/incident-mappers';

const me: Me = { id: 12, email: 'rita@campus.example', name: 'Rita' };
const dto: ApiIncident = {
  id: 'INC1001', title: 'VPN down', description: 'Cannot connect', createdAt: '2026-11-11T09:00:00Z', updatedAt: '2026-11-11T10:00:00Z',
  state: 'ASSIGNED', reporterId: 12, assigneeId: 13, categoryId: 7, serviceId: 3, priority: 'P2', assignedAt: '2026-11-11T09:30:00Z',
  firstResponseAt: null, resolvedAt: null, closedAt: null, availableTransitions: ['ACKNOWLEDGE'],
};

test.describe('live API mappers', () => {
  test('the signed-in user is recognised by id, everyone else is a stand-in that cannot be a real address', () => {
    expect(who(me, 12)).toEqual({ email: 'rita@campus.example', name: 'Rita' });
    expect(who(me, 13)).toEqual({ email: 'user-13@live.invalid', name: 'User #13' });
    expect(who(me, null)).toBeNull();
    expect(liveId(liveEmail(13))).toBe(13);
    expect(liveId('rita@campus.example')).toBeNull();
    expect(liveId('user-13@live.invalid.evil.com')).toBeNull();
  });

  test('an incident maps ownership by id, so the UI can tell whose it is', () => {
    const i = toIncident(dto, me);
    expect(i.reporterEmail).toBe(me.email);
    expect(i.assigneeEmail).toBe('user-13@live.invalid');
    expect(i).toMatchObject({ title: 'VPN down', priority: 'P2', category: 'Category #7', service: 'Service #3' });
    expect(toIncident({ ...dto, assigneeId: null, categoryId: null, serviceId: null, priority: null }, me))
      .toMatchObject({ assigneeEmail: null, assigneeName: null, category: 'Not categorised', service: 'Not set', priority: 'P3' });
  });

  test('timeline entries get stable ids and the author by id', () => {
    const e = toTimelineEntry({ type: 'NOTE', authorId: 13, visibility: 'INTERNAL', body: 'x', incidentState: 'IN_PROGRESS', createdAt: 't' }, me, 'INC1001', 2);
    expect(e).toMatchObject({ id: 'INC1001-2', authorName: 'User #13', visibility: 'INTERNAL' });
  });

  test('what the API does not say stays unknown instead of being invented', () => {
    expect(toSimilar({ incidentId: 'INC9', title: 't', resolution: 'r', resolvedAt: 't', sameService: true }))
      .toMatchObject({ priority: null, resolvedBy: null, daysToResolve: null, sameService: true });
    expect(toRecommendation({ technicianId: 13, name: 'Tech', score: 80, reasons: ['a'] })).toMatchObject({ technicianEmail: 'user-13@live.invalid', team: '' });
  });

  test('problem+json codes map to the errors the pages already handle', () => {
    const cases: [number, string, string][] = [
      [404, 'RESOURCE_NOT_FOUND', 'NOT_FOUND'], [403, 'FORBIDDEN', 'FORBIDDEN'], [401, 'UNAUTHENTICATED', 'FORBIDDEN'],
      [409, 'INVALID_STATE_TRANSITION', 'CONFLICT'], [409, 'DUPLICATE_RESOURCE', 'CONFLICT'], [409, 'WORKLOAD_EXCEEDED', 'CONFLICT'],
      [400, 'INVALID_INPUT', 'VALIDATION'], [503, 'SERVICE_UNAVAILABLE', 'UNAVAILABLE'],
    ];
    for (const [status, code, expected] of cases) expect(toIncidentError(status, { code, detail: 'd' }).code, code).toBe(expected);
  });

  test('field errors and the detail text carry through; garbage bodies fall back by status', () => {
    const e = toIncidentError(400, { code: 'INVALID_INPUT', detail: 'Please correct the highlighted fields', errors: [{ field: 'rating', message: 'must be between 1 and 5' }, { nope: 1 }] });
    expect(e.message).toBe('Please correct the highlighted fields');
    expect(e.fields).toEqual({ rating: 'must be between 1 and 5' });
    expect(toIncidentError(404, null).code).toBe('NOT_FOUND');
    expect(toIncidentError(500, 'oops').code).toBe('UNAVAILABLE');
    expect(toIncidentError(418, {}).message).toBe('Something went wrong. Please try again.');
  });

  test('a list row maps like the detail, with no description', () => {
    const { description: _d, availableTransitions: _a, assignedAt: _as, firstResponseAt: _f, closedAt: _c, createdAt, updatedAt, ...rest } = dto;
    const row = toIncidentFromSummary({ ...rest, createdAt, updatedAt, hasFeedback: false }, me);
    expect(row).toMatchObject({ id: 'INC1001', description: '', reporterEmail: me.email, assigneeEmail: 'user-13@live.invalid', state: 'ASSIGNED' });
  });

  test('the tabs partition every state exactly once, per role (the list counts rely on it)', () => {
    for (const role of ['EMPLOYEE', 'TECHNICIAN', 'ADMIN'] as const) {
      const states = TABS[role].flatMap((t) => t.states);
      expect(new Set(states).size, role).toBe(states.length);
    }
    expect(TABS.EMPLOYEE.flatMap((t) => t.states)).toHaveLength(9);
  });

  test('who has to act next', () => {
    const emp: Actor = { id: 12, email: me.email, name: 'Rita', role: 'EMPLOYEE' };
    const tech: Actor = { id: 13, email: 'user-13@live.invalid', name: 'T', role: 'TECHNICIAN' };
    const row = (state: string) => ({ state, reporterEmail: me.email, assigneeEmail: tech.email }) as Parameters<typeof needsAttention>[1];
    expect(needsAttention(tech, row('ASSIGNED'), false)).toBe(true);
    expect(needsAttention(tech, row('IN_PROGRESS'), false)).toBe(false);
    expect(needsAttention(emp, row('PENDING_INFO'), false)).toBe(true);
    expect(needsAttention(emp, row('VERIFIED'), false)).toBe(true);
    expect(needsAttention(emp, row('VERIFIED'), true)).toBe(false);
    expect(needsAttention({ ...emp, role: 'ADMIN' }, row('CLASSIFIED'), false)).toBe(true);
  });
});
