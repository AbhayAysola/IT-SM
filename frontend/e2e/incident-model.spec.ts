/**
 * Pure checks of the frontend copy of the P3 lifecycle rules (no browser pages). The backend is authoritative; these
 * tests pin the invariants the UI relies on, so a drift in the copy fails here.
 */
import { test, expect } from '@playwright/test';
import {
  ROLE_PERMISSIONS, STATES, TRANSITIONS, ALL_PERMISSIONS, applyTransition, availableTransitions, stateLabel, toActorRole,
  TRANSITION_LABEL, entryTypeFor, WORKLOAD_STATES,
} from '../src/features/incidents/model/lifecycle';
import { eligible, recommend, weightsFor, requireCanTake, type Candidate } from '../src/features/incidents/model/recommender';
import type { Actor, IncidentState, TransitionName } from '../src/features/incidents/model/types';

const admin: Actor = { email: 'a@campus.example', name: 'A', role: 'ADMIN' };
const tech: Actor = { email: 't@campus.example', name: 'T', role: 'TECHNICIAN' };
const emp: Actor = { email: 'e@campus.example', name: 'E', role: 'EMPLOYEE' };
const inc = { reporterEmail: emp.email, assigneeEmail: tech.email };
const names = Object.keys(TRANSITIONS) as TransitionName[];

test.describe('lifecycle model', () => {
  test('CLOSED is the only terminal state and every state can reach it', () => {
    for (const start of STATES) {
      const seen = new Set<IncidentState>([start]);
      const queue = [start];
      while (queue.length) {
        const s = queue.shift()!;
        for (const n of names) if (TRANSITIONS[n].from.includes(s) && !seen.has(TRANSITIONS[n].to)) { seen.add(TRANSITIONS[n].to); queue.push(TRANSITIONS[n].to); }
      }
      expect(seen.has('CLOSED'), `${start} cannot reach CLOSED`).toBe(true);
    }
    expect(names.filter((n) => TRANSITIONS[n].from.includes('CLOSED'))).toEqual([]);
  });

  test('exactly these moves require a reason', () => {
    expect(names.filter((n) => TRANSITIONS[n].requiresReason).sort()).toEqual(['PROVIDE_INFO', 'REASSIGN', 'REOPEN', 'REQUEST_INFO', 'RESOLVE']);
  });

  test('legality is checked before permission, then ownership, then reason', () => {
    expect(() => applyTransition('CLOSED', 'ASSIGN', emp, inc)).toThrow(/already moved on/);
    expect(() => applyTransition('CLASSIFIED', 'ASSIGN', emp, inc)).toThrow(/not allowed/);
    const stranger: Actor = { ...tech, email: 'other@campus.example' };
    expect(() => applyTransition('ASSIGNED', 'ACKNOWLEDGE', stranger, inc)).toThrow(/not allowed/);
    expect(() => applyTransition('IN_PROGRESS', 'RESOLVE', tech, inc, '  ')).toThrow(/explanation/);
    expect(applyTransition('IN_PROGRESS', 'RESOLVE', tech, inc, ' fixed ')).toEqual({ to: 'RESOLVED', reason: 'fixed' });
  });

  test('admins are exempt from ownership but not from legality', () => {
    expect(applyTransition('ASSIGNED', 'ACKNOWLEDGE', admin, inc).to).toBe('ACKNOWLEDGED');
    expect(() => applyTransition('NEW', 'CLOSE', admin, inc)).toThrow(/already moved on/);
  });

  test('reporter-side moves belong to the reporter, work moves to the assignee', () => {
    expect(availableTransitions('RESOLVED', emp, inc).sort()).toEqual(['REOPEN', 'VERIFY']);
    expect(availableTransitions('RESOLVED', tech, inc)).toEqual([]);
    expect(availableTransitions('ASSIGNED', tech, inc)).toEqual(['ACKNOWLEDGE']);
    expect(availableTransitions('ASSIGNED', emp, inc)).toEqual([]);
    expect(availableTransitions('CLASSIFIED', admin, inc)).toEqual(['ASSIGN']);
    expect(availableTransitions('CLOSED', admin, inc)).toEqual([]);
  });

  test('the backend permission gap is reproduced: a technician-reporter cannot verify', () => {
    expect(ROLE_PERMISSIONS.TECHNICIAN).not.toContain('INCIDENT_VERIFY_OWN');
    expect(ROLE_PERMISSIONS.EMPLOYEE).toContain('INCIDENT_VERIFY_OWN');
    expect(ROLE_PERMISSIONS.ADMIN).toEqual(ALL_PERMISSIONS);
    const selfReported = { reporterEmail: tech.email, assigneeEmail: tech.email };
    expect(availableTransitions('RESOLVED', tech, selfReported)).toEqual([]);
  });

  test('unknown or legacy roles get the least privilege', () => {
    expect(toActorRole('USER')).toBe('EMPLOYEE');
    expect(toActorRole('admin')).toBe('ADMIN');
    expect(toActorRole(' Technician ')).toBe('TECHNICIAN');
    expect(toActorRole('SUPERUSER')).toBe('EMPLOYEE');
    expect(toActorRole(undefined)).toBe('EMPLOYEE');
    expect(toActorRole('')).toBe('EMPLOYEE');
  });

  test('every state and move has wording for both audiences, and reporters get plain language', () => {
    for (const s of STATES) { expect(stateLabel(s, 'staff')).toBeTruthy(); expect(stateLabel(s, 'reporter')).toBeTruthy(); }
    for (const n of names) { expect(TRANSITION_LABEL[n].staff).toBeTruthy(); expect(TRANSITION_LABEL[n].reporter).toBeTruthy(); }
    expect(stateLabel('PENDING_INFO', 'reporter')).toBe('Waiting for your reply');
    expect(stateLabel('PENDING_INFO', 'staff')).toBe('Waiting for reporter');
  });

  test('every move that needs a reason leaves a timeline entry, and only those', () => {
    for (const n of names.filter((x) => TRANSITIONS[x].requiresReason)) expect(entryTypeFor(n), n).not.toBeNull();
    expect(entryTypeFor('ACKNOWLEDGE')).toBeNull();
    expect(WORKLOAD_STATES).toEqual(['ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO']);
  });
});

const cand = (email: string, over: Partial<Candidate> = {}): Candidate => ({
  email, name: email, team: 'T', available: true, maxWorkload: 5, activeCount: 0, experienceYears: 3, skills: {}, ...over,
});

test.describe('recommender model', () => {
  test('weights sum to one', () => {
    for (const p of ['P1', 'P2', 'P3', 'P4'] as const) { const w = weightsFor(p); expect(w.skill + w.headroom + w.experience).toBeCloseTo(1, 9); }
  });

  test('eligibility drops the unavailable, the full and the excluded', () => {
    const c = [cand('a'), cand('b', { available: false }), cand('c', { activeCount: 5 }), cand('d')];
    expect(eligible(c, ['d']).map((x) => x.email)).toEqual(['a']);
  });

  test('a perfect technician scores 100 and the worst 0', () => {
    const r = recommend([cand('best', { skills: { Wi: 5 }, experienceYears: 10 }), cand('worst', { activeCount: 5, experienceYears: 0 })], 'Wi', 'P3');
    expect(r.find((x) => x.technicianEmail === 'best')!.score).toBe(100);
    expect(r.find((x) => x.technicianEmail === 'worst')!.score).toBe(0);
  });

  test('urgency moves the ranking from free capacity to experience', () => {
    const veteranBusy = cand('vet', { activeCount: 4, experienceYears: 10, skills: { X: 3 } });
    const juniorFree = cand('jun', { activeCount: 0, experienceYears: 1, skills: { X: 3 } });
    expect(recommend([veteranBusy, juniorFree], 'X', 'P1')[0].technicianEmail).toBe('vet');
    expect(recommend([veteranBusy, juniorFree], 'X', 'P4')[0].technicianEmail).toBe('jun');
  });

  test('reasons are plain language and the list is capped at five', () => {
    const r = recommend([cand('a', { activeCount: 2, experienceYears: 1, skills: { X: 4 } })], 'X', 'P3');
    expect(r[0].reasons).toEqual(['Skill 4 of 5 in X', '2 of 5 active incidents', '1 year of experience']);
    expect(recommend(Array.from({ length: 8 }, (_, i) => cand(`t${i}`)), 'X', 'P3')).toHaveLength(5);
  });

  test('a person-chosen assignment is validated, and skill is not required', () => {
    expect(() => requireCanTake(cand('a', { available: false }))).toThrow(/not available/);
    expect(() => requireCanTake(cand('a', { activeCount: 5 }))).toThrow(/5 of 5/);
    expect(() => requireCanTake(cand('a', { skills: {} }))).not.toThrow();
  });
});
