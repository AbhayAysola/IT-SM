import { test, expect } from '@playwright/test';
import { call, toQuery, toServiceError } from '../src/features/admin/services/http';
import { ApiErrorResponse } from '../src/lib/api';
import { userApi, validateCreateUser, validateEditUser, validatePassword } from '../src/features/admin/services/user-api';
import { categoryApi, parseKeywords, toCategoryBody, validateCategory } from '../src/features/admin/services/category-api';
import { itServiceApi, validateService } from '../src/features/admin/services/it-service-api';
import { slaApi, slaCreateBody, validateSla } from '../src/features/admin/services/sla-api';
import { technicianApi, validateSkills, validateTechnician } from '../src/features/admin/services/technician-api';
import { assignmentApi, validateAssignment } from '../src/features/admin/services/assignment-api';
import { ServiceError } from '../src/features/admin/types';

/** Pure client-side checks: request construction, query encoding, DTO mapping, validation and error mapping. No browser. */

interface Sent { url: string; init: RequestInit }
function stubFetch(respond: (sent: Sent) => Response | Promise<Response>) {
  const sent: Sent[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (url: string, init: RequestInit = {}) => {
    const s = { url: String(url), init };
    sent.push(s);
    return respond(s);
  }) as typeof fetch;
  return { sent, restore: () => { globalThis.fetch = original; } };
}
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
const page = { items: [], page: 0, size: 10, totalItems: 0, totalPages: 0 };
const bodyOf = (s: Sent) => JSON.parse(String(s.init.body));

test.describe('query construction', () => {
  test('encodes keys and values and omits null, undefined and blank values', () => {
    expect(toQuery({ a: 'x y', b: undefined, c: null, d: '', e: '  ', f: 0, g: false, 'q&r': 'a=b&c' })).toBe('?a=x+y&f=0&g=false&q%26r=a%3Db%26c');
    expect(toQuery({})).toBe('');
    expect(toQuery({ search: 'ünï/çødé #1' })).toBe('?search=%C3%BCn%C3%AF%2F%C3%A7%C3%B8d%C3%A9+%231');
  });

  test('list calls send only the filters that are set, 0-based pages and the sort', async () => {
    const f = stubFetch(() => json(200, page));
    try {
      await userApi.list({ search: ' ada ', role: 'ALL', active: 'ALL', sort: 'name', dir: 'desc', page: 2, size: 25 });
      await userApi.list({ role: 'ADMIN', active: 'false' });
      await technicianApi.list({ available: 'true', teamLead: 'ALL', categoryId: 4 });
      await categoryApi.list({ search: 'vpn', active: 'true', sort: 'code' });
      await itServiceApi.list({ criticality: 5, ownerTeam: 'Infra & Ops', active: 'ALL' });
      await slaApi.list({ priority: 'P2', global: false, serviceId: 7 });
      const q = (i: number) => new URL(f.sent[i].url).searchParams.toString();
      expect(q(0)).toBe('search=ada&sort=name&dir=desc&page=2&size=25');
      expect(q(1)).toBe('role=ADMIN&active=false&page=0&size=10');
      expect(q(2)).toBe('available=true&categoryId=4&page=0&size=10');
      expect(q(3)).toBe('search=vpn&active=true&sort=code&page=0&size=10');
      expect(q(4)).toBe('criticality=5&ownerTeam=Infra+%26+Ops&page=0&size=10');
      expect(q(5)).toBe('priority=P2&serviceId=7&global=false&page=0&size=10');
      expect(f.sent.every((s) => new URL(s.url).origin === 'http://localhost:8080')).toBe(true);
    } finally { f.restore(); }
  });
});

test.describe('request construction and DTO mapping', () => {
  test('JSON bodies, verbs and paths for every mutation', async () => {
    const f = stubFetch(() => json(200, {}));
    try {
      await userApi.create({ email: ' A@b.co ', name: ' Ann ', password: 'pw-12345', role: 'TECHNICIAN' });
      await userApi.update(3, { email: 'a@b.co', name: 'Ann' }, 4);
      await userApi.changeRole(3, 'ADMIN', 4);
      await userApi.setActive(3, false, 4);
      await userApi.setActive(3, true, 5);
      await userApi.resetPassword(3, 'new-pass-1');
      await userApi.revokeSessions(3);
      const calls = f.sent.map((s) => `${s.init.method} ${new URL(s.url).pathname}`);
      expect(calls).toEqual([
        'POST /api/admin/users', 'PUT /api/admin/users/3', 'PUT /api/admin/users/3/role', 'POST /api/admin/users/3/deactivate',
        'POST /api/admin/users/3/activate', 'POST /api/admin/users/3/reset-password', 'POST /api/admin/users/3/revoke-sessions',
      ]);
      expect(bodyOf(f.sent[0])).toEqual({ email: 'A@b.co', name: 'Ann', password: 'pw-12345', role: 'TECHNICIAN' });
      expect(bodyOf(f.sent[1])).toEqual({ email: 'a@b.co', name: 'Ann', version: 4 });
      expect(bodyOf(f.sent[2])).toEqual({ role: 'ADMIN', version: 4 });
      expect(bodyOf(f.sent[3])).toEqual({ version: 4 });
      expect(bodyOf(f.sent[5])).toEqual({ password: 'new-pass-1' });
      expect(f.sent[6].init.body).toBeUndefined();
      expect((f.sent[0].init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    } finally { f.restore(); }
  });

  test('category, service, SLA, technician and assignment bodies match the backend DTOs', async () => {
    const settings = { activeStrategy: 'A_B', defaultMaxWorkload: 5, categoryOverrides: [], availableStrategies: ['A_B'], strategyCatalogStatus: 'AVAILABLE', version: 1, updatedAt: '', updatedByUserId: null };
    const f = stubFetch((sent) => json(200, sent.url.endsWith('/assignment-settings') ? settings : {}));
    try {
      await categoryApi.create({ code: ' net ', name: ' Network ', description: '  ', keywords: 'VPN,  Wi  Fi\nvpn2' });
      await categoryApi.update(1, { code: 'NET', name: 'Network', description: 'd', keywords: 'a1, b2' }, 7);
      await categoryApi.setActive(1, false);
      await itServiceApi.create({ name: ' Mail ', description: '', businessCriticality: 4, ownerTeam: ' ', dependsOnServiceIds: [2, 3] });
      await itServiceApi.update(9, { name: 'Mail', description: 'x', businessCriticality: 2, ownerTeam: 'IT', dependsOnServiceIds: [] }, 3);
      await slaApi.create({ serviceId: '', priority: 'P1', responseMinutes: '15', resolutionMinutes: '120', warningThresholdPct: '' });
      await slaApi.create({ serviceId: '8', priority: 'P2', responseMinutes: '30', resolutionMinutes: '240', warningThresholdPct: '70' });
      await slaApi.update(5, { serviceId: '8', priority: 'P2', responseMinutes: '31', resolutionMinutes: '241', warningThresholdPct: '' }, 2);
      await slaApi.effective(8, 'P3');
      await technicianApi.create({ userId: '12', maxWorkload: '6', experienceYears: '3', available: true, isTeamLead: false, reportsToUserId: '' }, [{ categoryId: 1, proficiency: 4 }]);
      await technicianApi.update(12, { userId: '12', maxWorkload: '7', experienceYears: '4', available: false, isTeamLead: true, reportsToUserId: '3' }, 2);
      await technicianApi.updateSkills(12, [], 3);
      await technicianApi.setActive(12, true, 3);
      await assignmentApi.update({ activeStrategy: ' SKILL_BASED ', defaultMaxWorkload: '8', overrides: [{ categoryId: 2, strategy: ' ROUND_ROBIN ' }] }, 6);
      const b = f.sent.map((s) => (s.init.body === undefined ? undefined : bodyOf(s)));
      expect(b[0]).toEqual({ code: 'net', name: 'Network', description: null, keywords: ['vpn', 'wi fi', 'vpn2'] });
      expect(b[1]).toEqual({ name: 'Network', description: 'd', keywords: ['a1', 'b2'], version: 7 });
      expect(b[2]).toBeUndefined();
      expect(b[3]).toEqual({ name: 'Mail', description: null, businessCriticality: 4, ownerTeam: null, dependsOnServiceIds: [2, 3] });
      expect(b[4]).toEqual({ name: 'Mail', description: 'x', businessCriticality: 2, ownerTeam: 'IT', dependsOnServiceIds: [], version: 3 });
      expect(b[5]).toEqual({ serviceId: null, priority: 'P1', responseMinutes: 15, resolutionMinutes: 120 });
      expect(b[6]).toEqual({ serviceId: 8, priority: 'P2', responseMinutes: 30, resolutionMinutes: 240, warningThresholdPct: 70 });
      expect(b[7]).toEqual({ responseMinutes: 31, resolutionMinutes: 241, version: 2 });
      expect(new URL(f.sent[8].url).search).toBe('?serviceId=8&priority=P3');
      expect(b[9]).toEqual({ userId: 12, maxWorkload: 6, experienceYears: 3, available: true, isTeamLead: false, reportsToUserId: null, skills: [{ categoryId: 1, proficiency: 4 }] });
      expect(b[10]).toEqual({ maxWorkload: 7, experienceYears: 4, available: false, isTeamLead: true, reportsToUserId: 3, version: 2 });
      expect(b[11]).toEqual({ skills: [], version: 3 });
      expect(b[12]).toEqual({ version: 3 });
      expect(b[13]).toEqual({ activeStrategy: 'SKILL_BASED', defaultMaxWorkload: 8, categoryOverrides: [{ categoryId: 2, strategy: 'ROUND_ROBIN' }], version: 6 });
      expect(f.sent.map((s) => s.init.method)).toEqual(['POST', 'PUT', 'POST', 'POST', 'PUT', 'POST', 'POST', 'PUT', 'GET', 'POST', 'PUT', 'PUT', 'POST', 'PUT']);
      // the old mock's fields never reach the wire
      expect(JSON.stringify(b)).not.toMatch(/department|defaultPriority|currentWorkload|shift|team"|fallback/);
    } finally { f.restore(); }
  });

  test('an empty (204) answer is accepted; a body that is not the page envelope is refused', async () => {
    let f = stubFetch(() => new Response(null, { status: 204 }));
    try { expect(await call('/api/admin/x', { method: 'POST' })).toEqual({}); } finally { f.restore(); }
    f = stubFetch(() => json(200, { items: [{ nope: true }], page: 0, size: 10, totalItems: 1, totalPages: 1 }));
    try {
      await expect(userApi.list({})).rejects.toMatchObject({ name: 'ServiceError', code: 'UNAVAILABLE' });
      await expect(categoryApi.list({})).rejects.toBeInstanceOf(ServiceError);
    } finally { f.restore(); }
    f = stubFetch(() => json(200, { activeStrategy: 5 }));
    try { await expect(assignmentApi.get()).rejects.toMatchObject({ code: 'UNAVAILABLE' }); } finally { f.restore(); }
  });
});

test.describe('assignment catalog response guard', () => {
  const base = { activeStrategy: 'A_B', defaultMaxWorkload: 5, categoryOverrides: [], version: 1, updatedAt: '', updatedByUserId: null };
  const read = async (extra: Record<string, unknown>) => {
    const f = stubFetch(() => json(200, { ...base, ...extra }));
    try { return await assignmentApi.get(); } finally { f.restore(); }
  };

  test('accepts all three states of the additive field', async () => {
    expect(await read({ strategyCatalogStatus: 'AVAILABLE', availableStrategies: ['A_B', 'C_D'] })).toMatchObject({ strategyCatalogStatus: 'AVAILABLE', availableStrategies: ['A_B', 'C_D'] });
    expect(await read({ strategyCatalogStatus: 'AVAILABLE', availableStrategies: [] })).toMatchObject({ strategyCatalogStatus: 'AVAILABLE', availableStrategies: [] });
    expect(await read({ strategyCatalogStatus: 'ABSENT', availableStrategies: null })).toMatchObject({ strategyCatalogStatus: 'ABSENT', availableStrategies: null });
    expect(await read({ strategyCatalogStatus: 'FAILED', availableStrategies: null })).toMatchObject({ strategyCatalogStatus: 'FAILED', availableStrategies: null });
  });

  test('the status, not the list, decides: ABSENT and FAILED stay distinct and null is never read as "empty"', async () => {
    const absent = await read({ strategyCatalogStatus: 'ABSENT', availableStrategies: null });
    const failed = await read({ strategyCatalogStatus: 'FAILED', availableStrategies: null });
    expect(absent.strategyCatalogStatus).not.toBe(failed.strategyCatalogStatus);
    expect(failed.availableStrategies).toBeNull();
  });

  test('a backend that predates the field is read from the list alone', async () => {
    expect(await read({ availableStrategies: ['A_B'] })).toMatchObject({ strategyCatalogStatus: 'AVAILABLE' });
    expect(await read({ availableStrategies: [] })).toMatchObject({ strategyCatalogStatus: 'AVAILABLE', availableStrategies: [] });
    expect(await read({ availableStrategies: null })).toMatchObject({ strategyCatalogStatus: 'ABSENT' });
  });

  test('refuses an unknown status and any status/list combination that contradicts itself', async () => {
    await expect(read({ strategyCatalogStatus: 'MAYBE', availableStrategies: [] })).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    await expect(read({ strategyCatalogStatus: 'AVAILABLE', availableStrategies: null })).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    await expect(read({ strategyCatalogStatus: 'ABSENT', availableStrategies: ['A_B'] })).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    await expect(read({ strategyCatalogStatus: 'FAILED', availableStrategies: [] })).rejects.toMatchObject({ code: 'UNAVAILABLE' });
    await expect(read({ strategyCatalogStatus: 'AVAILABLE', availableStrategies: [1, 2] })).rejects.toMatchObject({ code: 'UNAVAILABLE' });
  });

  test('a saved response to PUT goes through the same guard', async () => {
    const f = stubFetch(() => json(200, { ...base, strategyCatalogStatus: 'FAILED', availableStrategies: [] }));
    try { await expect(assignmentApi.update({ activeStrategy: 'A_B', defaultMaxWorkload: '5', overrides: [] }, 1)).rejects.toMatchObject({ code: 'UNAVAILABLE' }); } finally { f.restore(); }
  });

  test('only registered strategies can be saved (saved-but-unregistered keys are refused, not replaced)', () => {
    const form = { activeStrategy: 'OLD_ONE', defaultMaxWorkload: '5', overrides: [{ categoryId: 1, strategy: 'OLD_ONE' }, { categoryId: 2, strategy: 'A_B' }] };
    const errors = validateAssignment(form, ['A_B', 'C_D']);
    expect(errors.activeStrategy).toMatch(/“OLD_ONE” is not registered/);
    expect(errors['categoryOverrides[0].strategy']).toMatch(/not registered/);
    expect(errors['categoryOverrides[1].strategy']).toBeUndefined();
    expect(validateAssignment({ ...form, activeStrategy: 'A_B', overrides: [] }, ['A_B'])).toEqual({});
    expect(validateAssignment({ ...form, activeStrategy: 'A_B', overrides: [] }, [])).toHaveProperty('activeStrategy');
    expect(validateAssignment({ ...form, activeStrategy: 'A_B', overrides: [] })).toEqual({}); // no list known: the server decides
  });
});

test.describe('error mapping', () => {
  const err = (status: number, code?: string, message = 'server text', errors: { field: string; message: string }[] = []) =>
    toServiceError(new ApiErrorResponse(message, status, code, errors));

  test('each status becomes one safe, specific kind of error', () => {
    expect(err(401)).toMatchObject({ code: 'UNAUTHENTICATED' });
    expect(err(403, 'FORBIDDEN', 'secret detail')).toMatchObject({ code: 'FORBIDDEN', message: 'You don’t have permission to do this.' });
    expect(err(404, 'RESOURCE_NOT_FOUND', 'User 9 was not found')).toMatchObject({ code: 'NOT_FOUND', message: 'User 9 was not found' });
    expect(err(400, 'VALIDATION_FAILED', 'Validation failed')).toMatchObject({ code: 'VALIDATION' });
    expect(err(409, 'CONCURRENT_MODIFICATION', 'changed by someone else')).toMatchObject({ code: 'STALE', message: 'changed by someone else' });
    expect(err(409, 'DUPLICATE_RESOURCE', 'Email is already registered')).toMatchObject({ code: 'DUPLICATE' });
    expect(err(409, 'CONFLICT', 'last administrator')).toMatchObject({ code: 'CONFLICT', message: 'last administrator' });
    expect(err(409, 'REFERENCE_CONSTRAINT', 'in use')).toMatchObject({ code: 'CONFLICT' });
    expect(err(422, 'MISSING_SLA_CONFIG', 'No SLA')).toMatchObject({ code: 'UNPROCESSABLE', message: 'No SLA' });
    expect(err(503, 'SERVICE_UNAVAILABLE', 'catalog not available')).toMatchObject({ code: 'DEPENDENCY_UNAVAILABLE', message: 'catalog not available' });
  });

  test('5xx and network failures never show server text', () => {
    for (const s of [500, 502, 504]) {
      const e = err(s, 'INTERNAL_ERROR', 'java.lang.NullPointerException at X.kt:12');
      expect(e.code).toBe('UNAVAILABLE');
      expect(e.message).not.toMatch(/Exception|Kt|java/i);
    }
    const net = toServiceError(new TypeError('Failed to fetch'));
    expect(net).toMatchObject({ code: 'UNAVAILABLE' });
    expect(net.message).not.toMatch(/fetch/i);
  });

  test('field errors are carried over by their server field names, first one wins', () => {
    const e = err(400, 'VALIDATION_FAILED', 'Validation failed', [
      { field: 'keywords[2]', message: 'must be 2-30 characters' }, { field: 'keywords[2]', message: 'second' }, { field: 'skills[0].proficiency', message: 'must be between 1 and 5' },
    ]);
    expect(e.fieldErrors).toEqual({ 'keywords[2]': 'must be 2-30 characters', 'skills[0].proficiency': 'must be between 1 and 5' });
    expect(e.status).toBe(400);
  });

  test('a ServiceError passes through unchanged, and call() maps a failed response end to end', async () => {
    const own = new ServiceError('STALE', 'x');
    expect(toServiceError(own)).toBe(own);
    const f = stubFetch(() => new Response(JSON.stringify({ code: 'CONCURRENT_MODIFICATION', message: 'changed', detail: 'changed' }), { status: 409 }));
    try { await expect(call('/api/admin/users/1', { method: 'PUT', body: {} })).rejects.toMatchObject({ code: 'STALE', status: 409 }); } finally { f.restore(); }
  });
});

test.describe('form validation', () => {
  test('users', () => {
    expect(validateCreateUser({ email: '', name: '', password: '', role: 'EMPLOYEE' })).toEqual({ email: 'Enter an email address.', name: 'Enter a name.', password: 'Use at least 8 characters.' });
    expect(validateCreateUser({ email: 'a@b.co', name: 'A', password: 'x'.repeat(129), role: 'ADMIN' }).password).toBe('Use at most 128 characters.');
    expect(validateCreateUser({ email: 'not-an-email', name: 'x'.repeat(101), password: '12345678', role: 'ADMIN' })).toEqual({ email: 'Enter a valid email address.', name: 'Use at most 100 characters.' });
    expect(validateCreateUser({ email: 'a@b.co', name: 'A', password: '12345678', role: 'USER' as never }).role).toBe('Choose a role.');
    expect(validateEditUser({ email: 'a@b.co', name: 'A' })).toEqual({});
    expect(validatePassword('1234567')).toBeDefined(); expect(validatePassword('12345678')).toBeUndefined();
  });

  test('categories: immutable code rules, keyword normalisation, limits, duplicates', () => {
    const ok = { code: 'NET_2', name: 'Network', description: '', keywords: 'vpn, wi-fi' };
    expect(validateCategory(ok, 'create')).toEqual({});
    expect(validateCategory({ ...ok, code: 'net' }, 'create').code).toMatch(/UPPER_SNAKE_CASE/);
    expect(validateCategory({ ...ok, code: '' }, 'edit').code).toBeUndefined(); // not editable, not checked
    expect(validateCategory({ ...ok, name: 'ab' }, 'edit').name).toBe('Use 3–60 characters.');
    expect(validateCategory({ ...ok, description: 'x'.repeat(301) }, 'edit').description).toBeDefined();
    expect(validateCategory({ ...ok, keywords: '' }, 'edit').keywords).toBe('Add at least one keyword.');
    expect(validateCategory({ ...ok, keywords: Array.from({ length: 16 }, (_, i) => `kw${i}`).join(',') }, 'edit').keywords).toBe('Use at most 15 keywords.');
    expect(validateCategory({ ...ok, keywords: 'a' }, 'edit').keywords).toMatch(/2–30/);
    expect(validateCategory({ ...ok, keywords: 'ok1, bad!' }, 'edit').keywords).toMatch(/may only contain/);
    expect(validateCategory({ ...ok, keywords: 'VPN, vpn' }, 'edit').keywords).toMatch(/more than once/);
    expect(parseKeywords('  Wi   Fi , VPN\n\n,, x1 ')).toEqual(['wi fi', 'vpn', 'x1']);
    expect(toCategoryBody({ ...ok, description: ' hi ' }).description).toBe('hi');
  });

  test('services: criticality 1-5, self dependency, sizes', () => {
    const ok = { name: 'Mail', description: '', businessCriticality: 3, ownerTeam: '', dependsOnServiceIds: [] };
    expect(validateService(ok)).toEqual({});
    expect(validateService({ ...ok, businessCriticality: 6 }).businessCriticality).toBeDefined();
    expect(validateService({ ...ok, businessCriticality: 0 }).businessCriticality).toBeDefined();
    expect(validateService({ ...ok, businessCriticality: 2.5 }).businessCriticality).toBeDefined();
    expect(validateService({ ...ok, dependsOnServiceIds: [4] }, 4).dependsOnServiceIds).toBe('A service cannot depend on itself.');
    expect(validateService({ ...ok, name: 'ab' }).name).toBeDefined();
    expect(validateService({ ...ok, dependsOnServiceIds: Array.from({ length: 51 }, (_, i) => i + 1) }).dependsOnServiceIds).toBeDefined();
  });

  test('SLA: positive whole minutes, resolution longer than response, 30 day cap, 1-99 threshold', () => {
    const ok = { serviceId: '', priority: 'P1' as const, responseMinutes: '15', resolutionMinutes: '120', warningThresholdPct: '80' };
    expect(validateSla(ok, 'create')).toEqual({});
    expect(validateSla({ ...ok, responseMinutes: '0' }, 'create').responseMinutes).toBeDefined();
    expect(validateSla({ ...ok, responseMinutes: '1.5' }, 'create').responseMinutes).toBeDefined();
    expect(validateSla({ ...ok, resolutionMinutes: '15' }, 'create').resolutionMinutes).toBe('Must be longer than the response target.');
    expect(validateSla({ ...ok, resolutionMinutes: '43201' }, 'create').resolutionMinutes).toMatch(/43200/);
    expect(validateSla({ ...ok, resolutionMinutes: '43200' }, 'create')).toEqual({});
    expect(validateSla({ ...ok, warningThresholdPct: '0' }, 'create').warningThresholdPct).toBeDefined();
    expect(validateSla({ ...ok, warningThresholdPct: '100' }, 'create').warningThresholdPct).toBeDefined();
    expect(validateSla({ ...ok, warningThresholdPct: '' }, 'create')).toEqual({});
    expect(slaCreateBody({ ...ok, serviceId: '3' }).serviceId).toBe(3);
  });

  test('technicians and skills', () => {
    const ok = { userId: '5', maxWorkload: '5', experienceYears: '0', available: true, isTeamLead: false, reportsToUserId: '' };
    expect(validateTechnician(ok, 'create')).toEqual({});
    expect(validateTechnician({ ...ok, userId: '' }, 'create').userId).toBeDefined();
    expect(validateTechnician({ ...ok, userId: '' }, 'edit')).toEqual({});
    expect(validateTechnician({ ...ok, maxWorkload: '101' }, 'edit').maxWorkload).toBeDefined();
    expect(validateTechnician({ ...ok, experienceYears: '61' }, 'edit').experienceYears).toBeDefined();
    expect(validateTechnician({ ...ok, reportsToUserId: '5' }, 'edit', 5).reportsToUserId).toBeDefined();
    expect(validateSkills([{ categoryId: 1, proficiency: 3 }, { categoryId: 2, proficiency: 5 }])).toEqual({});
    expect(validateSkills([{ categoryId: 1, proficiency: 3 }, { categoryId: 1, proficiency: 4 }])['skills[1].categoryId']).toBeDefined();
    expect(validateSkills([{ categoryId: 1, proficiency: 0 }])['skills[0].proficiency']).toBeDefined();
    expect(validateSkills([{ categoryId: 1, proficiency: 6 }])['skills[0].proficiency']).toBeDefined();
    expect(validateSkills([{ categoryId: 0, proficiency: 3 }])['skills[0].categoryId']).toBeDefined();
    expect(validateSkills(Array.from({ length: 51 }, (_, i) => ({ categoryId: i + 1, proficiency: 1 }))).skills).toBeDefined();
  });

  test('assignment: strategy key, workload 1-100, one override per category', () => {
    const ok = { activeStrategy: 'ROUND_ROBIN', defaultMaxWorkload: '5', overrides: [{ categoryId: 1, strategy: 'SKILL_BASED' }] };
    expect(validateAssignment(ok)).toEqual({});
    expect(validateAssignment({ ...ok, activeStrategy: 'round robin' }).activeStrategy).toBeDefined();
    expect(validateAssignment({ ...ok, defaultMaxWorkload: '0' }).defaultMaxWorkload).toBeDefined();
    expect(validateAssignment({ ...ok, defaultMaxWorkload: '101' }).defaultMaxWorkload).toBeDefined();
    expect(validateAssignment({ ...ok, overrides: [{ categoryId: 1, strategy: 'A_B' }, { categoryId: 1, strategy: 'A_B' }] }).categoryOverrides).toBeDefined();
    expect(validateAssignment({ ...ok, overrides: [{ categoryId: 1, strategy: 'x' }] })['categoryOverrides[0].strategy']).toBeDefined();
  });
});
