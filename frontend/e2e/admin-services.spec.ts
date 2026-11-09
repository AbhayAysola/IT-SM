/**
 * Direct service-layer tests: these run the typed admin services in Node, with no browser, so every business rule
 * is enforced below the UI and not just in forms. A Map-backed localStorage shim exercises the real storage path.
 */
import { test, expect } from '@playwright/test';
import * as users from '../src/features/admin/services/user-service';
import * as techs from '../src/features/admin/services/technician-service';
import * as cats from '../src/features/admin/services/category-service';
import * as svcs from '../src/features/admin/services/service-service';
import * as sla from '../src/features/admin/services/sla-service';
import * as assign from '../src/features/admin/services/assignment-service';
import * as audit from '../src/features/admin/services/audit-service';

class MemoryStorage {
  private m = new Map<string, string>();
  getItem(k: string) { return this.m.has(k) ? this.m.get(k)! : null; }
  setItem(k: string, v: string) { this.m.set(k, String(v)); }
  removeItem(k: string) { this.m.delete(k); }
  clear() { this.m.clear(); }
}
const storage = new MemoryStorage();
(globalThis as unknown as { localStorage: MemoryStorage }).localStorage = storage;

// The services only touch localStorage inside calls, never at import time, so static imports see the shim.
const M = { users, techs, cats, svcs, sla, assign, audit };
test.beforeEach(() => storage.clear());
test.describe.configure({ mode: 'serial' }); // one shared in-memory store per worker

const AUDIT_KEY = 'itsm_admin_v1_audit';
const auditRaw = () => storage.getItem(AUDIT_KEY);
const auditEntries = () => JSON.parse(storage.getItem(AUDIT_KEY) ?? '[]') as { entity: string; action: string; entityId: string; before?: Record<string, string>; after?: Record<string, string> }[];
/** Asserts the call rejects with `code`, and that the audit log is byte-for-byte unchanged afterwards. */
async function rejectsWithoutAudit(p: () => Promise<unknown>, code: string, message?: RegExp) {
  await M.audit.auditService.recent(1); // make sure the audit store is initialised
  const before = auditRaw();
  const err = await p().then(() => null, (e: { code?: string; message: string }) => e);
  expect(err, 'expected the call to reject').not.toBeNull();
  expect(err!.code).toBe(code);
  if (message) expect(err!.message).toMatch(message);
  expect(auditRaw()).toBe(before);
}

test.describe('storage robustness', () => {
  for (const bad of ['null', '{}', '"text"', '42', 'not json{']) {
    test(`a stored value of ${bad} is reseeded instead of crashing`, async () => {
      storage.setItem('itsm_admin_v1_users', bad);
      const page = await M.users.userService.list({ pageSize: 50 });
      expect(page.total).toBe(25);
    });
  }

  test('non-object entries inside a stored array are ignored', async () => {
    const seed = (await M.users.userService.list({ pageSize: 50 })).items;
    storage.setItem('itsm_admin_v1_users', JSON.stringify([null, 7, 'x', seed[0]]));
    expect((await M.users.userService.list()).total).toBe(1);
  });

  test('a stale or corrupt assignment record is treated as not configured', async () => {
    storage.setItem('itsm_admin_v1_assignment', JSON.stringify([{ strategy: 'TELEPATHY' }]));
    expect(await M.assign.assignmentService.get()).toBeNull();
    storage.setItem('itsm_admin_v1_assignment', JSON.stringify([{ strategy: 'MANUAL', fallbackStrategy: 'ROUND_ROBIN' }]));
    expect(await M.assign.assignmentService.get()).toBeNull();
  });

  test('stale references are shown as unknown rather than crashing', async () => {
    const t = (await M.techs.technicianService.list()).items[0];
    storage.setItem('itsm_admin_v1_technicians', JSON.stringify([{ ...t, userId: 'u-gone' }]));
    expect((await M.techs.technicianService.list()).items[0].name).toBe('Unknown user');
  });
});

test.describe('users', () => {
  test('duplicate emails are rejected case-insensitively, without an audit entry', async () => {
    await rejectsWithoutAudit(() => M.users.userService.create({ name: 'Dup', email: 'PRIYA.Natarajan@campus.example', role: 'EMPLOYEE', department: 'Finance' }), 'DUPLICATE');
    await rejectsWithoutAudit(() => M.users.userService.update('u-009', { name: 'Elena', email: 'amara.okafor@campus.example', role: 'EMPLOYEE', department: 'Finance' }), 'DUPLICATE');
  });

  test('invalid input is rejected by the service, not only by the form', async () => {
    await rejectsWithoutAudit(() => M.users.userService.create({ name: 'X', email: 'nope', role: 'EMPLOYEE', department: '' }), 'VALIDATION');
  });

  test('the last active administrator cannot be suspended, demoted or deleted', async () => {
    await M.users.userService.setStatus('u-002', 'SUSPENDED');
    await rejectsWithoutAudit(() => M.users.userService.setStatus('u-001', 'SUSPENDED'), 'CONFLICT', /only active administrator/);
    await rejectsWithoutAudit(() => M.users.userService.update('u-001', { name: 'Amara Okafor', email: 'amara.okafor@campus.example', role: 'EMPLOYEE', department: 'Information Technology' }), 'CONFLICT');
    await rejectsWithoutAudit(() => M.users.userService.remove('u-001'), 'CONFLICT');
  });

  test('bulk suspension records one audit entry per user with before/after values, and skips the last admin', async () => {
    await M.users.userService.setStatus('u-002', 'SUSPENDED');
    const n = auditEntries().length;
    const res = await M.users.userService.setStatusMany(['u-009', 'u-010', 'u-001'], 'SUSPENDED');
    expect(res).toEqual({ changed: 2, skipped: ['Amara Okafor'] });
    const added = auditEntries().slice(0, auditEntries().length - n);
    expect(added.map((e) => e.entityId).sort()).toEqual(['u-009', 'u-010']);
    for (const e of added) expect(e).toMatchObject({ action: 'STATUS_CHANGE', before: { status: 'ACTIVE' }, after: { status: 'SUSPENDED' } });
  });

  test('updates record only the changed fields', async () => {
    await M.users.userService.update('u-009', { name: 'Elena Petrova', email: 'elena.petrova@campus.example', role: 'EMPLOYEE', department: 'Library' });
    expect(auditEntries()[0]).toMatchObject({ action: 'UPDATE', before: { department: 'Finance' }, after: { department: 'Library' } });
    expect(Object.keys(auditEntries()[0].after!)).toEqual(['department']);
  });
});

test.describe('technicians', () => {
  test('workload limits are enforced by the service', async () => {
    const valid = { skills: ['Hardware'], skillLevel: 'SENIOR' as const, availability: 'AVAILABLE' as const, isTeamLead: false, team: 'Service Desk', shift: 'Day (08:00-16:00)' };
    await rejectsWithoutAudit(() => M.techs.technicianService.update('t-001', { ...valid, maxWorkload: 4 }), 'CONFLICT', /below the current workload/); // holds 5
    await rejectsWithoutAudit(() => M.techs.technicianService.update('t-001', { ...valid, maxWorkload: 21 }), 'VALIDATION');
    await rejectsWithoutAudit(() => M.techs.technicianService.update('t-001', { ...valid, skills: [], maxWorkload: 8 }), 'VALIDATION');
    await rejectsWithoutAudit(() => M.techs.technicianService.remove('t-001'), 'CONFLICT', /active incident/);
  });
});

test.describe('categories', () => {
  const input = (over: Partial<{ name: string; keywords: string[] }> = {}) => ({ name: 'New Category', description: 'A perfectly valid description.', keywords: ['brand new'], defaultPriority: 'P3' as const, ...over });

  test('duplicate names (case-insensitive) and active keyword collisions are rejected', async () => {
    await rejectsWithoutAudit(() => M.cats.categoryService.create(input({ name: '  hardware ' })), 'DUPLICATE');
    await rejectsWithoutAudit(() => M.cats.categoryService.create(input({ keywords: ['LAPTOP'] })), 'CONFLICT', /“laptop”.*“Hardware”/);
    await rejectsWithoutAudit(() => M.cats.categoryService.create(input({ keywords: ['ok', 'ok'] })), 'VALIDATION');
  });

  test('reactivation re-checks keyword collisions', async () => {
    await M.cats.categoryService.setStatus('c-004', 'INACTIVE'); // Hardware
    await M.cats.categoryService.create(input({ keywords: ['laptop'] }));
    await rejectsWithoutAudit(() => M.cats.categoryService.setStatus('c-004', 'ACTIVE'), 'CONFLICT');
  });

  test('the last active category cannot be deactivated', async () => {
    const active = (await M.cats.categoryService.list({ status: 'ACTIVE', pageSize: 50 })).items;
    for (const c of active.slice(1)) await M.cats.categoryService.setStatus(c.id, 'INACTIVE');
    await rejectsWithoutAudit(() => M.cats.categoryService.setStatus(active[0].id, 'INACTIVE'), 'CONFLICT', /At least one category/);
  });
});

test.describe('IT services', () => {
  const svc = (dependsOn: string[], name = 'Core Network') => ({ name, description: 'Campus backbone, switching, routing and DNS/DHCP.', ownerTeam: 'Infrastructure', criticality: 'CRITICAL' as const, dependsOn });

  test('direct, indirect and self-referencing cycles are rejected by the service', async () => {
    // Core Network <- Identity <- LMS, so Core Network -> LMS closes a 3-step loop.
    await rejectsWithoutAudit(() => M.svcs.itServiceService.update('s-001', svc(['s-006'])), 'VALIDATION', /circular dependency: Core Network → Learning Management System → Identity & Single Sign-On → Core Network/);
    await rejectsWithoutAudit(() => M.svcs.itServiceService.update('s-001', svc(['s-003'])), 'VALIDATION', /circular/); // direct
    await rejectsWithoutAudit(() => M.svcs.itServiceService.update('s-001', svc(['s-001'])), 'VALIDATION', /itself/);
    await rejectsWithoutAudit(() => M.svcs.itServiceService.update('s-001', svc(['s-011'])), 'VALIDATION', /inactive/);
  });

  test('wouldCreateCycle agrees with the service for the picker', async () => {
    const all = await M.svcs.itServiceService.options();
    expect(M.svcs.wouldCreateCycle(all, 's-001', 's-010')).toBe(true); // library -> db -> core
    expect(M.svcs.wouldCreateCycle(all, 's-009', 's-005')).toBe(false);
  });

  test('deactivation and reactivation respect dependencies', async () => {
    await rejectsWithoutAudit(() => M.svcs.itServiceService.setStatus('s-001', 'INACTIVE'), 'CONFLICT', /is a dependency of/);
    const a = await M.svcs.itServiceService.create(svc(['s-010'], 'Reading Lists'));
    await M.svcs.itServiceService.setStatus(a.id, 'INACTIVE');
    await M.svcs.itServiceService.setStatus('s-010', 'INACTIVE');
    await rejectsWithoutAudit(() => M.svcs.itServiceService.setStatus(a.id, 'ACTIVE'), 'CONFLICT', /depends on inactive Library Catalogue/);
  });

  test('a stale dependency reference does not block saving', async () => {
    const list = await M.svcs.itServiceService.options();
    storage.setItem('itsm_admin_v1_services', JSON.stringify(list.map((s) => (s.id === 's-009' ? { ...s, dependsOn: ['s-001', 's-gone'] } : s))));
    const saved = await M.svcs.itServiceService.update('s-009', { ...svc(['s-001', 's-gone'], 'Print Services'), criticality: 'LOW' });
    expect(saved.dependsOn).toEqual(['s-001']);
  });
});

test.describe('SLA policies', () => {
  test('targets, duplicates, priority consistency and inactive services are enforced by the service', async () => {
    const s = M.sla.slaService;
    await rejectsWithoutAudit(() => s.create({ serviceId: 's-004', priority: 'P2', responseMinutes: 0, resolutionMinutes: 60 }), 'VALIDATION');
    await rejectsWithoutAudit(() => s.create({ serviceId: 's-004', priority: 'P2', responseMinutes: 60, resolutionMinutes: 60 }), 'VALIDATION', /longer than the response/);
    await rejectsWithoutAudit(() => s.create({ serviceId: 's-004', priority: 'P2', responseMinutes: 1.5, resolutionMinutes: 60 }), 'VALIDATION');
    await rejectsWithoutAudit(() => s.create({ serviceId: 's-004', priority: 'P2', responseMinutes: 5, resolutionMinutes: 43201 }), 'VALIDATION', /30 days/);
    await rejectsWithoutAudit(() => s.create({ serviceId: 's-001', priority: 'P1', responseMinutes: 5, resolutionMinutes: 60 }), 'DUPLICATE');
    await rejectsWithoutAudit(() => s.create({ serviceId: 's-011', priority: 'P1', responseMinutes: 5, resolutionMinutes: 60 }), 'VALIDATION', /inactive/);
    // Core Network P2 is 15 min / 4 h; a P3 can't be faster than P2, a P1 edit can't be slower.
    await rejectsWithoutAudit(() => s.update('p-003', { responseMinutes: 10, resolutionMinutes: 1440 }), 'VALIDATION', /cannot be shorter/);
    await rejectsWithoutAudit(() => s.update('p-001', { responseMinutes: 5, resolutionMinutes: 300 }), 'VALIDATION', /cannot be longer/);
  });

  test('a successful update records before/after targets', async () => {
    await M.sla.slaService.update('p-001', { responseMinutes: 5, resolutionMinutes: 180 });
    expect(auditEntries()[0]).toMatchObject({ entity: 'SLA_POLICY', before: { resolutionMinutes: '120' }, after: { resolutionMinutes: '180' } });
  });
});

test.describe('assignment settings (configuration only)', () => {
  const base = async () => {
    const r = (await M.assign.assignmentService.get())!;
    const { updatedAt: _a, updatedBy: _b, ...s } = r;
    return s;
  };

  test('invalid configurations are rejected by the service without audit', async () => {
    const s = await base();
    await rejectsWithoutAudit(() => M.assign.assignmentService.save({ ...s, strategy: 'ROUND_ROBIN', fallbackStrategy: 'ROUND_ROBIN' }), 'VALIDATION', /different/);
    await rejectsWithoutAudit(() => M.assign.assignmentService.save({ ...s, strategy: 'ROUND_ROBIN', fallbackStrategy: 'MANUAL', roundRobin: { ...s.roundRobin, pool: 'BY_TEAM', teams: ['Security'] } }), 'VALIDATION', /No technician profiles in Security/);
    await rejectsWithoutAudit(() => M.assign.assignmentService.save({ ...s, strategy: 'ROUND_ROBIN', fallbackStrategy: 'MANUAL', roundRobin: { ...s.roundRobin, pool: 'BY_TEAM', teams: ['Made Up'] } }), 'VALIDATION', /does not exist/);
    await rejectsWithoutAudit(() => M.assign.assignmentService.save({ ...s, skillBased: { ...s.skillBased, minSkillMatchPct: 0 } }), 'VALIDATION');
    await rejectsWithoutAudit(() => M.assign.assignmentService.save({ ...s, unassignedEscalationMinutes: 4 }), 'VALIDATION');
    await rejectsWithoutAudit(() => M.assign.assignmentService.save({ ...s, recommendationCount: 6 }), 'VALIDATION');
    await rejectsWithoutAudit(() => M.assign.assignmentService.save(s), 'CONFLICT', /Nothing has changed/);
  });

  test('team pools are checked against technician data at save time', async () => {
    const s = await base();
    const rr = { ...s, strategy: 'ROUND_ROBIN' as const, fallbackStrategy: 'MANUAL' as const, roundRobin: { ...s.roundRobin, pool: 'BY_TEAM' as const, teams: ['Applications'] } };
    storage.setItem('itsm_admin_v1_technicians', '[]'); // nobody left in Applications
    await rejectsWithoutAudit(() => M.assign.assignmentService.save(rr), 'VALIDATION', /No technician profiles in Applications/);
  });

  test('a successful save audits a flattened before/after diff', async () => {
    const s = await base();
    await M.assign.assignmentService.save({ ...s, recommendationCount: 1 });
    expect(auditEntries()[0]).toMatchObject({ entity: 'ASSIGNMENT_SETTINGS', before: { recommendationCount: '3' }, after: { recommendationCount: '1' } });
  });
});

test.describe('audit log', () => {
  test('the viewer service exposes no way to edit or delete entries', async () => {
    expect(Object.keys(M.audit.auditService).sort()).toEqual(['get', 'list', 'recent']);
  });

  test('new entries are prepended and earlier entries are never modified', async () => {
    await M.audit.auditService.recent(1);
    const before = auditEntries();
    await M.users.userService.setStatus('u-011', 'SUSPENDED');
    const after = auditEntries();
    expect(after.length).toBe(before.length + 1);
    expect(after.slice(1)).toEqual(before);
  });

  test('invalid date ranges are rejected', async () => {
    await expect(M.audit.auditService.list({ from: '2026-11-05', to: '2026-11-01' })).rejects.toMatchObject({ code: 'VALIDATION' });
    await expect(M.audit.auditService.list({ from: '05/11/2026' })).rejects.toMatchObject({ code: 'VALIDATION' });
  });
});
