/**
 * A stateful, in-memory stand-in for the P1 admin API, used by the hermetic Playwright suite. It follows the real
 * contract (docs/architecture/admin-frontend-contract.md): numeric ids, `version` optimistic locking (409
 * CONCURRENT_MODIFICATION), duplicate and protection rules (409), validation (400 with `errors`), the 422 for a missing
 * SLA policy and the 503 for a missing assignment catalog. The backend's own suite proves the real semantics; this
 * fake exists so the UI can be exercised against the same shapes without a database.
 */
export interface FakeResult { status: number; body?: unknown }
export interface RecordedRequest { method: string; path: string; query: URLSearchParams; body: unknown }

type Row = Record<string, unknown> & { id: number; version: number };

const now = () => new Date().toISOString();
const problem = (status: number, code: string, detail: string, errors?: { field: string; message: string }[]): FakeResult => ({
  status, body: { type: `urn:itsm:error:${code.toLowerCase().replace(/_/g, '-')}`, title: 'Error', status, detail, code, message: detail, traceId: 'fake', timestamp: now(), ...(errors ? { errors } : {}) },
});
const invalid = (field: string, message: string) => problem(400, 'VALIDATION_FAILED', 'Validation failed', [{ field, message }]);
const stale = (what: string) => problem(409, 'CONCURRENT_MODIFICATION', `This ${what} was changed by someone else. Reload it and apply your change again.`);
const conflict = (message: string) => problem(409, 'CONFLICT', message);
const duplicate = (message: string) => problem(409, 'DUPLICATE_RESOURCE', message);
const notFound = (what: string, id: unknown) => problem(404, 'RESOURCE_NOT_FOUND', `${what} ${id} was not found`);

function paged<T>(rows: T[], q: URLSearchParams) {
  const size = Number(q.get('size') ?? 20), page = Number(q.get('page') ?? 0);
  return { status: 200, body: { items: rows.slice(page * size, page * size + size), page, size, totalItems: rows.length, totalPages: Math.ceil(rows.length / size) } };
}
function sorted<T>(rows: T[], q: URLSearchParams, keys: Record<string, (r: T) => string | number>, fallback: string): T[] {
  const get = keys[q.get('sort') ?? fallback] ?? keys[fallback];
  const m = (q.get('dir') ?? 'asc') === 'desc' ? -1 : 1;
  return [...rows].sort((a, b) => { const x = get(a), y = get(b); return (x < y ? -1 : x > y ? 1 : 0) * m; });
}
const flag = (q: URLSearchParams, k: string) => (q.has(k) ? q.get(k) === 'true' : undefined);

export class AdminFake {
  users: (Row & { email: string; name: string; role: string; active: boolean; createdAt: string; updatedAt: string })[] = [];
  technicians: (Row & Record<string, any>)[] = [];
  categories: (Row & Record<string, any>)[] = [];
  services: (Row & Record<string, any>)[] = [];
  sla: (Row & Record<string, any>)[] = [];
  assignment: Record<string, any> = {};
  escalation: Record<string, any> = {};
  /**
   * The strategy catalog, as B9's contract reports it. `null` = ABSENT (GET: `strategyCatalogStatus: 'ABSENT'`,
   * `availableStrategies: null`; PUT: 503). An array = AVAILABLE (possibly empty: nothing registered, every PUT 400).
   * `catalogFailed = true` = FAILED (GET: status FAILED, list null; PUT: generic 500, no detail).
   */
  strategies: string[] | null = ['ROUND_ROBIN', 'SKILL_BASED'];
  catalogFailed = false;
  /** Set to false to answer like a backend that predates `strategyCatalogStatus`. */
  reportsCatalogStatus = true;
  requests: RecordedRequest[] = [];
  /** The next `times` requests whose "METHOD /path" starts with `match` answer `status` instead of being handled. */
  failures: { match: string; status: number; code: string; detail: string; times: number }[] = [];
  /** Email of the signed-in administrator, so self-protection rules can apply. */
  selfEmail = '';
  private next = { user: 1, category: 1, service: 1, sla: 1 };

  constructor() { this.reset(); }

  reset() {
    this.requests = []; this.failures = []; this.strategies = ['ROUND_ROBIN', 'SKILL_BASED']; this.catalogFailed = false; this.reportsCatalogStatus = true; this.selfEmail = '';
    this.next = { user: 1, category: 1, service: 1, sla: 1 };
    this.users = []; this.technicians = []; this.categories = []; this.services = []; this.sla = [];
    ['Ada Lovelace:ADMIN', 'Grace Hopper:ADMIN', 'Linus Torvalds:TECHNICIAN', 'Margaret Hamilton:TECHNICIAN', 'Alan Turing:TECHNICIAN', 'Edsger Dijkstra:EMPLOYEE', 'Barbara Liskov:EMPLOYEE']
      .forEach((s) => { const [name, role] = s.split(':'); this.addUser(name, role); });
    this.users[6].active = false;
    ['NETWORK:Network:vpn,wifi', 'HARDWARE:Hardware:laptop,monitor', 'EMAIL:Email:outlook,mailbox'].forEach((s) => { const [code, name, k] = s.split(':'); this.addCategory(code, name, k.split(',')); });
    this.addService('Email', 4, 'Messaging', []); this.addService('VPN', 5, 'Infrastructure', []); this.addService('Intranet', 2, null, [2]);
    this.addSla(null, 'P1', 15, 120); this.addSla(null, 'P2', 30, 240); this.addSla(null, 'P3', 60, 480); this.addSla(null, 'P4', 120, 1440); this.addSla(2, 'P1', 5, 60);
    this.addTechnician(3, 5, 4, [{ categoryId: 1, proficiency: 4 }], true); this.addTechnician(4, 8, 10, [{ categoryId: 2, proficiency: 5 }, { categoryId: 3, proficiency: 2 }]);
    this.assignment = { activeStrategy: 'ROUND_ROBIN', defaultMaxWorkload: 5, categoryOverrides: [], version: 0, updatedAt: now(), updatedByUserId: null };
    this.escalation = { unassignedMinutes: 30, secondLevelPct: 150, adminLevelPct: 200, version: 0, updatedAt: now(), updatedByUserId: null };
  }

  addUser(name: string, role: string, email = `${name.toLowerCase().replace(/[^a-z]+/g, '.')}@example.test`) {
    const id = this.next.user++;
    const u = { id, email, name, role, active: true, version: 0, createdAt: now(), updatedAt: now() };
    this.users.push(u); return u;
  }
  addCategory(code: string, name: string, keywords: string[]) {
    const id = this.next.category++;
    const c = { id, code, name, description: null, keywords: [...keywords].sort(), active: true, version: 0, createdAt: now(), updatedAt: now() };
    this.categories.push(c); return c;
  }
  addService(name: string, businessCriticality: number, ownerTeam: string | null, dependsOn: number[]) {
    const id = this.next.service++;
    const s = { id, name, description: null, businessCriticality, ownerTeam, active: true, dependsOnIds: dependsOn, version: 0, createdAt: now(), updatedAt: now() };
    this.services.push(s); return s;
  }
  addSla(serviceId: number | null, priority: string, responseMinutes: number, resolutionMinutes: number) {
    const id = this.next.sla++;
    const p = { id, serviceId, priority, responseMinutes, resolutionMinutes, warningThresholdPct: 80, active: true, version: 0, createdAt: now(), updatedAt: now() };
    this.sla.push(p); return p;
  }
  addTechnician(userId: number, maxWorkload: number, experienceYears: number, skills: { categoryId: number; proficiency: number }[], isTeamLead = false) {
    const t = { id: userId, userId, active: true, available: true, maxWorkload, experienceYears, isTeamLead, reportsToUserId: null, skills, version: 0, createdAt: now(), updatedAt: now() };
    this.technicians.push(t); return t;
  }
  /** Simulates someone else saving first. */
  bump(list: 'users' | 'technicians' | 'categories' | 'services' | 'sla', id: number) { const r = this[list].find((x) => x.id === id)!; r.version += 1; r.updatedAt = now(); }
  last(method: string, pathStart: string) { return [...this.requests].reverse().find((r) => r.method === method && r.path.startsWith(pathStart)); }
  count(method: string, pathStart: string) { return this.requests.filter((r) => r.method === method && r.path.startsWith(pathStart)).length; }

  // ---- views (the exact response shapes) ----
  private userView = (u: AdminFake['users'][number]) => ({ ...u });
  private ref = (id: number) => { const s = this.services.find((x) => x.id === id)!; return { id: s.id, name: s.name, active: s.active }; };
  private serviceView = (s: Row & Record<string, any>) => ({
    id: s.id, name: s.name, description: s.description, businessCriticality: s.businessCriticality, ownerTeam: s.ownerTeam, active: s.active,
    dependsOn: (s.dependsOnIds as number[]).map(this.ref).sort((a, b) => a.name.localeCompare(b.name)),
    dependents: this.services.filter((x) => (x.dependsOnIds as number[]).includes(s.id)).map((x) => this.ref(x.id)).sort((a, b) => a.name.localeCompare(b.name)),
    version: s.version, createdAt: s.createdAt, updatedAt: s.updatedAt,
  });
  private slaView = (p: Row & Record<string, any>) => ({
    id: p.id, service: p.serviceId === null ? null : this.ref(p.serviceId), priority: p.priority, responseMinutes: p.responseMinutes, resolutionMinutes: p.resolutionMinutes,
    warningThresholdPct: p.warningThresholdPct, active: p.active, version: p.version, createdAt: p.createdAt, updatedAt: p.updatedAt,
  });
  private techView = (t: Row & Record<string, any>) => {
    const u = this.users.find((x) => x.id === t.userId)!;
    return {
      userId: t.userId, name: u.name, email: u.email, userRole: u.role, userActive: u.active, active: t.active, available: t.available, maxWorkload: t.maxWorkload,
      experienceYears: t.experienceYears, isTeamLead: t.isTeamLead, reportsToUserId: t.reportsToUserId,
      skills: (t.skills as { categoryId: number; proficiency: number }[]).map((s) => { const c = this.categories.find((x) => x.id === s.categoryId)!; return { categoryId: c.id, categoryCode: c.code, categoryName: c.name, categoryActive: c.active, proficiency: s.proficiency }; }),
      version: t.version, createdAt: t.createdAt, updatedAt: t.updatedAt,
    };
  };
  private assignmentView = () => ({
    ...this.assignment,
    ...(this.reportsCatalogStatus ? { strategyCatalogStatus: this.catalogFailed ? 'FAILED' : this.strategies === null ? 'ABSENT' : 'AVAILABLE' } : {}),
    availableStrategies: this.catalogFailed || !this.strategies ? null : [...this.strategies].sort(),
    categoryOverrides: (this.assignment.categoryOverrides as { categoryId: number; strategy: string }[]).map((o) => {
      const c = this.categories.find((x) => x.id === o.categoryId)!; return { categoryId: c.id, categoryName: c.name, categoryActive: c.active, strategy: o.strategy };
    }),
  });

  handle(req: RecordedRequest): FakeResult {
    this.requests.push(req);
    const key = `${req.method} ${req.path}`;
    const f = this.failures.find((x) => key.startsWith(x.match) && x.times > 0);
    if (f) { f.times -= 1; return problem(f.status, f.code, f.detail); }
    const parts = req.path.replace('/api/admin/', '').split('/');
    const [area, id, action] = [parts[0], parts[1], parts[2]];
    switch (area) {
      case 'users': return this.usersApi(req, id, action);
      case 'technicians': return this.techApi(req, id, action);
      case 'categories': return this.categoriesApi(req, id, action);
      case 'services': return this.servicesApi(req, id, action);
      case 'sla-policies': return this.slaApi(req, id, action);
      case 'assignment-settings': return this.assignmentApi(req);
      case 'escalation-settings': return this.escalationApi(req);
      case 'analytics': return this.analyticsApi(req);
      default: return problem(404, 'RESOURCE_NOT_FOUND', 'Not found');
    }
  }

  // ---- users ----
  private usersApi({ method, query: q, body }: RecordedRequest, id?: string, action?: string): FakeResult {
    const b = (body ?? {}) as Record<string, any>;
    if (!id) {
      if (method === 'GET') {
        const s = (q.get('search') ?? '').toLowerCase();
        const rows = this.users.filter((u) => (!s || u.name.toLowerCase().includes(s) || u.email.includes(s)) && (!q.get('role') || u.role === q.get('role')) && (flag(q, 'active') === undefined || u.active === flag(q, 'active')));
        return paged(sorted(rows, q, { email: (u) => u.email, name: (u) => u.name.toLowerCase(), role: (u) => u.role, createdAt: (u) => u.createdAt, active: (u) => String(u.active) }, 'email').map(this.userView), q);
      }
      if (method === 'POST') {
        if (typeof b.password !== 'string' || b.password.length < 8) return invalid('password', 'size must be between 8 and 128');
        if (!['EMPLOYEE', 'TECHNICIAN', 'ADMIN'].includes(b.role)) return invalid('role', 'must be one of [EMPLOYEE, TECHNICIAN, ADMIN]');
        if (this.users.some((u) => u.email === String(b.email).toLowerCase())) return duplicate('Email is already registered');
        const u = this.addUser(b.name, b.role, String(b.email).toLowerCase());
        return { status: 201, body: this.userView(u) };
      }
    }
    const u = this.users.find((x) => x.id === Number(id));
    if (!u) return notFound('User', id);
    const lastAdmin = u.role === 'ADMIN' && u.active && this.users.filter((x) => x.role === 'ADMIN' && x.active).length === 1;
    const isSelf = u.email === this.selfEmail;
    if (!action && method === 'GET') return { status: 200, body: this.userView(u) };
    if (action === 'reset-password' && method === 'POST') {
      if (typeof b.password !== 'string' || b.password.length < 8) return invalid('password', 'size must be between 8 and 128');
      u.version += 1; return { status: 200, body: this.userView(u) };
    }
    if (action === 'revoke-sessions' && method === 'POST') { u.version += 1; return { status: 200, body: this.userView(u) }; }
    if (b.version !== u.version) return stale('user');
    if (!action && method === 'PUT') {
      if (this.users.some((x) => x.id !== u.id && x.email === String(b.email).toLowerCase())) return duplicate('Email is already registered');
      u.name = b.name; u.email = String(b.email).toLowerCase();
    } else if (action === 'role' && method === 'PUT') {
      if (isSelf) return conflict('You cannot change your own administrator role; ask another administrator.');
      if (lastAdmin && b.role !== 'ADMIN') return conflict('This is the last active administrator and cannot be demoted or deactivated.');
      if (u.role === 'TECHNICIAN' && b.role !== 'TECHNICIAN' && this.technicians.some((t) => t.userId === u.id && t.active)) {
        return conflict('This user has an active technician profile; deactivate the profile before changing the role.');
      }
      u.role = b.role;
    } else if ((action === 'activate' || action === 'deactivate') && method === 'POST') {
      if (action === 'deactivate' && isSelf) return conflict('You cannot deactivate your own account; ask another administrator.');
      if (action === 'deactivate' && lastAdmin) return conflict('This is the last active administrator and cannot be demoted or deactivated.');
      u.active = action === 'activate';
    } else return problem(405, 'METHOD_NOT_ALLOWED', 'Method not allowed');
    u.version += 1; u.updatedAt = now();
    return { status: 200, body: this.userView(u) };
  }

  // ---- technicians ----
  private techApi({ method, query: q, body }: RecordedRequest, id?: string, action?: string): FakeResult {
    const b = (body ?? {}) as Record<string, any>;
    const detail = (): FakeResult | null => {
      if (!(b.maxWorkload >= 1 && b.maxWorkload <= 100)) return invalid('maxWorkload', 'must be between 1 and 100');
      if (!(b.experienceYears >= 0 && b.experienceYears <= 60)) return invalid('experienceYears', 'must be between 0 and 60');
      return null;
    };
    const skillsOk = (skills: { categoryId: number; proficiency: number }[]): FakeResult | null => {
      for (let i = 0; i < skills.length; i++) {
        const s = skills[i];
        if (!this.categories.some((c) => c.id === s.categoryId)) return invalid(`skills[${i}].categoryId`, `category ${s.categoryId} does not exist`);
        if (!(s.proficiency >= 1 && s.proficiency <= 5)) return invalid(`skills[${i}].proficiency`, 'must be between 1 and 5');
      }
      return null;
    };
    if (!id) {
      if (method === 'GET') {
        const s = (q.get('search') ?? '').toLowerCase();
        const rows = this.technicians.map(this.techView).filter((t) => (!s || t.name.toLowerCase().includes(s) || t.email.includes(s)) &&
          (flag(q, 'active') === undefined || t.active === flag(q, 'active')) && (flag(q, 'available') === undefined || t.available === flag(q, 'available')) &&
          (flag(q, 'teamLead') === undefined || t.isTeamLead === flag(q, 'teamLead')) && (!q.get('categoryId') || t.skills.some((k) => k.categoryId === Number(q.get('categoryId')))));
        return paged(sorted(rows, q, { name: (t) => t.name.toLowerCase(), email: (t) => t.email, maxWorkload: (t) => t.maxWorkload, experienceYears: (t) => t.experienceYears, createdAt: (t) => t.createdAt }, 'name'), q);
      }
      if (method === 'POST') {
        const user = this.users.find((x) => x.id === b.userId);
        if (!user) return invalid('userId', `user ${b.userId} does not exist`);
        if (user.role !== 'TECHNICIAN') return conflict(`User ${user.id} has role ${user.role}; only a TECHNICIAN can have a technician profile. Change the role in user administration first.`);
        if (!user.active) return conflict(`User ${user.id} is deactivated.`);
        if (this.technicians.some((t) => t.userId === user.id)) return duplicate(`User ${user.id} already has a technician profile`);
        const bad = detail() ?? skillsOk(b.skills ?? []); if (bad) return bad;
        const t = this.addTechnician(user.id, b.maxWorkload, b.experienceYears, b.skills ?? [], !!b.isTeamLead);
        t.available = !!b.available; t.reportsToUserId = b.reportsToUserId ?? null;
        return { status: 201, body: this.techView(t) };
      }
    }
    const t = this.technicians.find((x) => x.userId === Number(id));
    if (!t) return notFound('Technician profile', id);
    if (!action && method === 'GET') return { status: 200, body: this.techView(t) };
    if (b.version !== t.version) return stale('technician profile');
    if (!action && method === 'PUT') {
      const bad = detail(); if (bad) return bad;
      if (b.reportsToUserId === t.userId) return invalid('reportsToUserId', 'A technician cannot report to themselves.');
      Object.assign(t, { maxWorkload: b.maxWorkload, experienceYears: b.experienceYears, available: b.available, isTeamLead: b.isTeamLead, reportsToUserId: b.reportsToUserId ?? null });
    } else if (action === 'skills' && method === 'PUT') {
      const bad = skillsOk(b.skills ?? []); if (bad) return bad;
      const ids = (b.skills as { categoryId: number }[]).map((s) => s.categoryId);
      const dupIdx = ids.findIndex((c, i) => ids.indexOf(c) !== i);
      if (dupIdx >= 0) return invalid(`skills[${dupIdx}].categoryId`, `category ${ids[dupIdx]} is listed more than once`);
      t.skills = b.skills;
    } else if ((action === 'activate' || action === 'deactivate') && method === 'POST') {
      if (action === 'deactivate' && this.technicians.some((x) => x.active && x.reportsToUserId === t.userId)) return conflict('Active technicians still report to this technician; move them before deactivating the profile.');
      t.active = action === 'activate';
    } else return problem(405, 'METHOD_NOT_ALLOWED', 'Method not allowed');
    t.version += 1; t.updatedAt = now();
    return { status: 200, body: this.techView(t) };
  }

  // ---- categories ----
  private categoriesApi({ method, query: q, body }: RecordedRequest, id?: string, action?: string): FakeResult {
    const b = (body ?? {}) as Record<string, any>;
    const kw = (list: unknown): { ok: string[] } | FakeResult => {
      const arr = (Array.isArray(list) ? list : []).map((k) => String(k ?? '').trim().toLowerCase().replace(/\s+/g, ' '));
      const bad = arr.findIndex((k) => k.length < 2 || k.length > 30);
      if (bad >= 0) return invalid(`keywords[${bad}]`, 'must be 2-30 characters');
      const dup = arr.find((k, i) => arr.indexOf(k) !== i);
      if (dup) return invalid('keywords', `duplicates after normalisation: [${dup}]`);
      return { ok: arr };
    };
    if (!id) {
      if (method === 'GET') {
        const s = (q.get('search') ?? '').toLowerCase();
        const rows = this.categories.filter((c) => (!s || c.code.toLowerCase().includes(s) || c.name.toLowerCase().includes(s) || (c.keywords as string[]).some((k) => k.includes(s))) && (flag(q, 'active') === undefined || c.active === flag(q, 'active')));
        return paged(sorted(rows, q, { name: (c) => c.name.toLowerCase(), code: (c) => c.code, updatedAt: (c) => c.updatedAt, createdAt: (c) => c.createdAt }, 'name'), q);
      }
      if (method === 'POST') {
        if (!/^[A-Z][A-Z0-9_]{0,39}$/.test(String(b.code ?? '').trim())) return invalid('code', 'must be UPPER_SNAKE_CASE');
        if (this.categories.some((c) => c.code.toLowerCase() === String(b.code).trim().toLowerCase())) return duplicate(`A category with code '${String(b.code).trim()}' already exists`);
        if (this.categories.some((c) => c.name.toLowerCase() === String(b.name).trim().toLowerCase())) return duplicate(`A category named '${String(b.name).trim()}' already exists`);
        const k = kw(b.keywords); if (!('ok' in k)) return k;
        const c = this.addCategory(String(b.code).trim(), String(b.name).trim(), k.ok); c.description = b.description ?? null;
        return { status: 201, body: { ...c } };
      }
    }
    const c = this.categories.find((x) => x.id === Number(id));
    if (!c) return notFound('Category', id);
    if (!action && method === 'GET') return { status: 200, body: { ...c } };
    if (!action && method === 'PUT') {
      if (b.version !== c.version) return stale('category');
      if (this.categories.some((x) => x.id !== c.id && x.name.toLowerCase() === String(b.name).trim().toLowerCase())) return duplicate(`A category named '${String(b.name).trim()}' already exists`);
      const k = kw(b.keywords); if (!('ok' in k)) return k;
      Object.assign(c, { name: String(b.name).trim(), description: b.description ?? null, keywords: k.ok.sort() });
    } else if ((action === 'activate' || action === 'deactivate') && method === 'POST') {
      if (action === 'deactivate' && this.categories.filter((x) => x.active).length === 1 && c.active) return conflict('At least one category must stay active so new incidents can be classified.');
      c.active = action === 'activate';
    } else return problem(405, 'METHOD_NOT_ALLOWED', 'Method not allowed');
    c.version += 1; c.updatedAt = now();
    return { status: 200, body: { ...c } };
  }

  // ---- IT services ----
  private servicesApi({ method, query: q, body }: RecordedRequest, id?: string, action?: string): FakeResult {
    const b = (body ?? {}) as Record<string, any>;
    const check = (selfId?: number): FakeResult | null => {
      const deps: number[] = b.dependsOnServiceIds ?? [];
      if (!(b.businessCriticality >= 1 && b.businessCriticality <= 5)) return invalid('businessCriticality', 'must be between 1 and 5');
      if (selfId !== undefined && deps.includes(selfId)) return problem(400, 'INVALID_INPUT', 'A service cannot depend on itself', [{ field: 'dependsOnServiceIds', message: 'must not contain the service itself' }]);
      const missing = deps.find((d) => !this.services.some((s) => s.id === d));
      if (missing !== undefined) return problem(400, 'INVALID_INPUT', 'Unknown dependency', [{ field: 'dependsOnServiceIds', message: `service ${missing} does not exist` }]);
      if (selfId !== undefined) {
        const reaches = (from: number, seen = new Set<number>()): boolean => from === selfId || (!seen.has(from) && (seen.add(from), (this.services.find((s) => s.id === from)!.dependsOnIds as number[]).some((n) => reaches(n, seen))));
        if (deps.some((d) => reaches(d))) return conflict('These dependencies would create a cycle.');
      }
      return null;
    };
    if (!id) {
      if (method === 'GET') {
        const s = (q.get('search') ?? '').toLowerCase();
        const rows = this.services.filter((x) => (!s || x.name.toLowerCase().includes(s) || String(x.ownerTeam ?? '').toLowerCase().includes(s)) && (flag(q, 'active') === undefined || x.active === flag(q, 'active')) &&
          (!q.get('criticality') || x.businessCriticality === Number(q.get('criticality'))) && (!q.get('ownerTeam') || String(x.ownerTeam ?? '').toLowerCase() === q.get('ownerTeam')!.toLowerCase()));
        return paged(sorted(rows, q, { name: (x) => x.name.toLowerCase(), businessCriticality: (x) => x.businessCriticality, updatedAt: (x) => x.updatedAt, createdAt: (x) => x.createdAt }, 'name').map(this.serviceView), q);
      }
      if (method === 'POST') {
        if (this.services.some((s) => s.name.toLowerCase() === String(b.name).trim().toLowerCase())) return duplicate(`An IT service named '${String(b.name).trim()}' already exists`);
        const bad = check(); if (bad) return bad;
        const s = this.addService(String(b.name).trim(), b.businessCriticality, b.ownerTeam ?? null, b.dependsOnServiceIds ?? []); s.description = b.description ?? null;
        return { status: 201, body: this.serviceView(s) };
      }
    }
    const s = this.services.find((x) => x.id === Number(id));
    if (!s) return problem(404, 'SERVICE_NOT_FOUND', `IT service ${id} was not found`);
    if (!action && method === 'GET') return { status: 200, body: this.serviceView(s) };
    if (!action && method === 'PUT') {
      if (b.version !== s.version) return stale('IT service');
      if (this.services.some((x) => x.id !== s.id && x.name.toLowerCase() === String(b.name).trim().toLowerCase())) return duplicate(`An IT service named '${String(b.name).trim()}' already exists`);
      const bad = check(s.id); if (bad) return bad;
      Object.assign(s, { name: String(b.name).trim(), description: b.description ?? null, businessCriticality: b.businessCriticality, ownerTeam: b.ownerTeam ?? null, dependsOnIds: b.dependsOnServiceIds });
    } else if ((action === 'activate' || action === 'deactivate') && method === 'POST') {
      if (action === 'deactivate') {
        const dependents = this.services.filter((x) => x.active && (x.dependsOnIds as number[]).includes(s.id));
        if (dependents.length) return conflict(`Active services still depend on this one: ${dependents.map((d) => d.name).join(', ')}. Remove those dependencies first.`);
      }
      s.active = action === 'activate';
    } else return problem(405, 'METHOD_NOT_ALLOWED', 'Method not allowed');
    s.version += 1; s.updatedAt = now();
    return { status: 200, body: this.serviceView(s) };
  }

  // ---- SLA policies ----
  private slaApi({ method, query: q, body }: RecordedRequest, id?: string, action?: string): FakeResult {
    const b = (body ?? {}) as Record<string, any>;
    const targets = (): FakeResult | null => {
      if (!(b.responseMinutes >= 1)) return invalid('responseMinutes', 'must be a positive number of minutes');
      if (!(b.resolutionMinutes >= 1)) return invalid('resolutionMinutes', 'must be a positive number of minutes');
      if (b.resolutionMinutes > 43200) return invalid('resolutionMinutes', 'must be at most 43200 minutes (30 days)');
      if (b.responseMinutes >= b.resolutionMinutes) return invalid('resolutionMinutes', 'must be longer than the response target');
      if (b.warningThresholdPct != null && !(b.warningThresholdPct >= 1 && b.warningThresholdPct <= 99)) return invalid('warningThresholdPct', 'must be between 1 and 99');
      return null;
    };
    if (id === 'effective' && method === 'GET') {
      const sid = Number(q.get('serviceId')), pr = q.get('priority');
      if (!this.services.some((s) => s.id === sid)) return problem(404, 'SERVICE_NOT_FOUND', `IT service ${sid} was not found`);
      const own = this.sla.find((p) => p.active && p.serviceId === sid && p.priority === pr);
      const glob = this.sla.find((p) => p.active && p.serviceId === null && p.priority === pr);
      const p = own ?? glob;
      if (!p) return problem(422, 'MISSING_SLA_CONFIG', `No active SLA policy exists for service ${sid} and priority ${pr}, and there is no global ${pr} policy.`);
      return { status: 200, body: { serviceId: sid, priority: pr, scope: own ? 'SERVICE' : 'GLOBAL', policyId: p.id, responseMinutes: p.responseMinutes, resolutionMinutes: p.resolutionMinutes, warningThresholdPct: p.warningThresholdPct } };
    }
    if (!id) {
      if (method === 'GET') {
        const s = (q.get('search') ?? '').toLowerCase();
        const rows = this.sla.filter((p) => (!s || (p.serviceId !== null && this.ref(p.serviceId).name.toLowerCase().includes(s))) && (flag(q, 'active') === undefined || p.active === flag(q, 'active')) &&
          (!q.get('priority') || p.priority === q.get('priority')) && (!q.get('serviceId') || p.serviceId === Number(q.get('serviceId'))) &&
          (flag(q, 'global') === undefined || (p.serviceId === null) === flag(q, 'global')));
        return paged(sorted(rows, q, { priority: (p) => p.priority, responseMinutes: (p) => p.responseMinutes, resolutionMinutes: (p) => p.resolutionMinutes, updatedAt: (p) => p.updatedAt, createdAt: (p) => p.createdAt }, 'priority').map(this.slaView), q);
      }
      if (method === 'POST') {
        if (!['P1', 'P2', 'P3', 'P4'].includes(String(b.priority))) return invalid('priority', 'must be one of P1, P2, P3, P4');
        const bad = targets(); if (bad) return bad;
        if (b.serviceId != null && !this.services.some((s) => s.id === b.serviceId)) return problem(404, 'SERVICE_NOT_FOUND', `IT service ${b.serviceId} was not found`);
        if (this.sla.some((p) => p.active && p.serviceId === (b.serviceId ?? null) && p.priority === b.priority)) return duplicate(`An active ${b.priority} policy already exists for this scope`);
        const p = this.addSla(b.serviceId ?? null, b.priority, b.responseMinutes, b.resolutionMinutes); p.warningThresholdPct = b.warningThresholdPct ?? 80;
        return { status: 201, body: this.slaView(p) };
      }
    }
    const p = this.sla.find((x) => x.id === Number(id));
    if (!p) return notFound('SLA policy', id);
    if (!action && method === 'GET') return { status: 200, body: this.slaView(p) };
    if (!action && method === 'PUT') {
      if (b.version !== p.version) return stale('SLA policy');
      const bad = targets(); if (bad) return bad;
      Object.assign(p, { responseMinutes: b.responseMinutes, resolutionMinutes: b.resolutionMinutes, warningThresholdPct: b.warningThresholdPct ?? p.warningThresholdPct });
    } else if ((action === 'activate' || action === 'deactivate') && method === 'POST') {
      if (action === 'activate' && this.sla.some((x) => x.id !== p.id && x.active && x.serviceId === p.serviceId && x.priority === p.priority)) return duplicate(`An active ${p.priority} policy already exists for this scope`);
      p.active = action === 'activate';
    } else return problem(405, 'METHOD_NOT_ALLOWED', 'Method not allowed');
    p.version += 1; p.updatedAt = now();
    return { status: 200, body: this.slaView(p) };
  }

  // ---- assignment settings ----
  private assignmentApi({ method, body }: RecordedRequest): FakeResult {
    if (method === 'GET') return { status: 200, body: this.assignmentView() };
    if (method !== 'PUT') return problem(405, 'METHOD_NOT_ALLOWED', 'Method not allowed');
    const b = (body ?? {}) as Record<string, any>;
    if (b.version !== this.assignment.version) return stale('assignment configuration');
    if (!(b.defaultMaxWorkload >= 1 && b.defaultMaxWorkload <= 100)) return invalid('defaultMaxWorkload', 'must be between 1 and 100');
    if (this.catalogFailed) return problem(500, 'INTERNAL_ERROR', 'An unexpected error occurred');
    if (this.strategies === null) return problem(503, 'SERVICE_UNAVAILABLE', 'Assignment settings cannot be changed right now: the assignment strategy catalog is not available, so strategy keys cannot be verified. Try again later.');
    if (!this.strategies.includes(b.activeStrategy)) return invalid('activeStrategy', `'${b.activeStrategy}' is not a registered strategy`);
    const overrides = (b.categoryOverrides ?? []) as { categoryId: number; strategy: string }[];
    const badIdx = overrides.findIndex((o) => !this.strategies!.includes(o.strategy));
    if (badIdx >= 0) return invalid(`categoryOverrides[${badIdx}].strategy`, `'${overrides[badIdx].strategy}' is not a registered strategy`);
    Object.assign(this.assignment, { activeStrategy: b.activeStrategy, defaultMaxWorkload: b.defaultMaxWorkload, categoryOverrides: overrides, version: this.assignment.version + 1, updatedAt: now(), updatedByUserId: 1 });
    return { status: 200, body: this.assignmentView() };
  }

  // ---- escalation settings (P4): the same rules as EscalationSettings.validate ----
  private escalationApi({ method, body }: RecordedRequest): FakeResult {
    if (method === 'GET') return { status: 200, body: { ...this.escalation } };
    if (method !== 'PUT') return problem(405, 'METHOD_NOT_ALLOWED', 'Method not allowed');
    const b = (body ?? {}) as Record<string, any>;
    const errors: { field: string; message: string }[] = [];
    if (!(Number.isInteger(b.unassignedMinutes) && b.unassignedMinutes >= 1 && b.unassignedMinutes <= 10080)) errors.push({ field: 'unassignedMinutes', message: 'must be between 1 and 10080 minutes (one week)' });
    if (!(Number.isInteger(b.secondLevelPct) && b.secondLevelPct >= 101 && b.secondLevelPct <= 1000)) errors.push({ field: 'secondLevelPct', message: 'must be between 101 and 1000' });
    if (!(Number.isInteger(b.adminLevelPct) && b.adminLevelPct >= 101 && b.adminLevelPct <= 1000)) errors.push({ field: 'adminLevelPct', message: 'must be between 101 and 1000' });
    else if (b.adminLevelPct <= b.secondLevelPct) errors.push({ field: 'adminLevelPct', message: 'must be greater than secondLevelPct' });
    if (errors.length) return problem(400, 'INVALID_INPUT', 'Invalid escalation settings', errors);
    if (b.version !== this.escalation.version) return stale('escalation configuration');
    Object.assign(this.escalation, { unassignedMinutes: b.unassignedMinutes, secondLevelPct: b.secondLevelPct, adminLevelPct: b.adminLevelPct, version: this.escalation.version + 1, updatedAt: now(), updatedByUserId: 1 });
    return { status: 200, body: { ...this.escalation } };
  }

  // ---- analytics (P4): a deterministic summary for whatever range is asked, validated like the backend ----
  private analyticsApi({ method, query: q }: RecordedRequest): FakeResult {
    if (method !== 'GET') return problem(405, 'METHOD_NOT_ALLOWED', 'Method not allowed');
    const from = q.get('from') ?? '2026-11-01', to = q.get('to') ?? '2026-11-30';
    const days = Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000) + 1;
    if (!(days >= 1)) return invalid('from', 'must not be after to');
    if (days > 366) return invalid('to', 'the range can be at most 366 days');
    const counts = (rm: number, rb: number, sm: number, sb: number) => ({
      tracked: rm + rb, responseMet: rm, responseBreached: rb, responseCompliancePct: rm + rb ? Math.round((rm * 1000) / (rm + rb)) / 10 : null,
      resolutionMet: sm, resolutionBreached: sb, resolutionCompliancePct: sm + sb ? Math.round((sm * 1000) / (sm + sb)) / 10 : null,
    });
    const perDay = Array.from({ length: days }, (_, k) => ({
      date: new Date(Date.parse(from) + k * 86_400_000).toISOString().slice(0, 10), reported: (k * 7) % 5, resolved: (k * 3) % 4, breaches: k % 3 === 0 ? 1 : 0, escalations: k % 4 === 0 ? 1 : 0,
    }));
    return {
      status: 200,
      body: {
        from, to,
        incidents: { reported: perDay.reduce((n, d) => n + d.reported, 0), resolved: perDay.reduce((n, d) => n + d.resolved, 0), openNow: 12, unassignedNow: 3, openByState: { CLASSIFIED: 3, IN_PROGRESS: 9 }, openByPriority: { P1: 2, P2: 4, P3: 6 } },
        sla: {
          overall: counts(40, 10, 36, 9),
          byPriority: [{ id: 'P1', name: 'P1', counts: counts(5, 3, 4, 4) }, { id: 'P2', name: 'P2', counts: counts(15, 3, 12, 3) }, { id: 'P3', name: 'P3', counts: counts(20, 4, 20, 2) }],
          byService: [{ id: '2', name: 'VPN', counts: counts(10, 2, 9, 3) }, { id: '1', name: 'Email', counts: counts(30, 8, 27, 6) }],
          byCategory: [{ id: '1', name: 'Network', counts: counts(25, 6, 22, 5) }],
        },
        times: { toRespond: { count: 50, meanMinutes: 18.4, p90Minutes: 41 }, toResolve: { count: 45, meanMinutes: 312, p90Minutes: 1500 } },
        escalations: { total: perDay.reduce((n, d) => n + d.escalations, 0), byTrigger: { RESPONSE_BREACHED: 4, UNASSIGNED: 2, RESOLUTION_OVERDUE: 1 } },
        perDay,
        technicians: [{ userId: 3, name: 'Linus Torvalds', maxWorkload: 5, activeIncidents: 4, resolved: 11, ratings: 6, averageRating: 4.5 }, { userId: 4, name: 'Margaret Hamilton', maxWorkload: 8, activeIncidents: 2, resolved: 7, ratings: 0, averageRating: null }],
      },
    };
  }
}
