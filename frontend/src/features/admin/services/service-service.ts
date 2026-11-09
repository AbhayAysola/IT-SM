import { SEED_SERVICES, SEED_SLA_POLICIES } from '../data/seed-config';
import {
  ActiveStatus, Criticality, ItService, ListQuery, Page, ServiceError, ServiceView, SlaPolicy,
} from '../types';
import { diffFields, recordAudit } from './audit-service';
import { includesText, loadCollection, newId, paginate, saveCollection, simulate } from './store';

export interface ServiceQuery extends ListQuery {
  status?: ActiveStatus | 'ALL';
  criticality?: Criticality | 'ALL';
  team?: string | 'ALL';
  sort?: 'name' | 'criticality' | 'updatedAt';
  dir?: 'asc' | 'desc';
}

export interface ServiceInput {
  name: string;
  description: string;
  ownerTeam: string;
  criticality: Criticality;
  dependsOn: string[];
}

const RANK: Record<Criticality, number> = { CRITICAL: 4, HIGH: 3, MEDIUM: 2, LOW: 1 };

export function validateServiceInput(i: ServiceInput): Partial<Record<keyof ServiceInput, string>> {
  const e: Partial<Record<keyof ServiceInput, string>> = {};
  const name = i.name.trim();
  if (name.length < 3) e.name = 'Enter a service name (at least 3 characters).';
  else if (name.length > 60) e.name = 'Name must be 60 characters or fewer.';
  const d = i.description.trim();
  if (d.length < 10) e.description = 'Describe what the service provides (at least 10 characters).';
  else if (d.length > 300) e.description = 'Description must be 300 characters or fewer.';
  if (!i.ownerTeam) e.ownerTeam = 'Choose the team that owns this service.';
  if (!i.criticality) e.criticality = 'Choose a business criticality.';
  return e;
}

/**
 * Finds a dependency path `from -> ... -> to` following `dependsOn`, using `override` for one service's deps.
 * Used to detect cycles: if `id` would depend on `dep` and `dep` already reaches `id`, that path is the cycle.
 */
export function findDependencyPath(services: ItService[], from: string, to: string, override?: { id: string; dependsOn: string[] }): string[] | null {
  const deps = (id: string) => (override && override.id === id ? override.dependsOn : services.find((s) => s.id === id)?.dependsOn ?? []);
  const seen = new Set<string>();
  const walk = (cur: string, path: string[]): string[] | null => {
    if (cur === to) return [...path, cur];
    if (seen.has(cur)) return null;
    seen.add(cur);
    for (const d of deps(cur)) {
      const r = walk(d, [...path, cur]);
      if (r) return r;
    }
    return null;
  };
  return walk(from, []);
}

/** Would making `id` depend on `depId` create a cycle (including depending on itself)? */
export const wouldCreateCycle = (services: ItService[], id: string | null, depId: string): boolean =>
  id !== null && (id === depId || findDependencyPath(services, depId, id) !== null);

const all = () => loadCollection<ItService>('services', SEED_SERVICES);
const save = (s: ItService[]) => saveCollection('services', s);
const policies = () => loadCollection<SlaPolicy>('sla', SEED_SLA_POLICIES);

function assertDependencies(list: ItService[], id: string | null, name: string, deps: string[]) {
  if (new Set(deps).size !== deps.length) throw new ServiceError('VALIDATION', 'A dependency is listed more than once.');
  for (const d of deps) {
    const dep = list.find((s) => s.id === d);
    if (!dep) throw new ServiceError('VALIDATION', 'One of the selected dependencies no longer exists.');
    if (id && d === id) throw new ServiceError('VALIDATION', `${name} cannot depend on itself.`);
    if (dep.status === 'INACTIVE') throw new ServiceError('VALIDATION', `${dep.name} is inactive and cannot be used as a dependency.`);
  }
  if (id) {
    for (const d of deps) {
      const path = findDependencyPath(list, d, id, { id, dependsOn: deps });
      if (path) {
        const names = [name, ...path.slice(0, -1).map((p) => list.find((s) => s.id === p)?.name ?? p), name];
        throw new ServiceError('VALIDATION', `That would create a circular dependency: ${names.join(' → ')}.`);
      }
    }
  }
}

function view(s: ItService, list: ItService[], pols: SlaPolicy[]): ServiceView {
  const nameOf = (id: string) => list.find((x) => x.id === id)?.name ?? 'Unknown service';
  return {
    ...s,
    dependencyNames: s.dependsOn.map(nameOf),
    dependentNames: list.filter((x) => x.status === 'ACTIVE' && x.dependsOn.includes(s.id)).map((x) => x.name),
    policyCount: pols.filter((p) => p.serviceId === s.id).length,
  };
}

function assertNameFree(list: ItService[], selfId: string | null, name: string) {
  if (list.some((s) => s.id !== selfId && s.name.trim().toLowerCase() === name.trim().toLowerCase())) {
    throw new ServiceError('DUPLICATE', `A service named “${name.trim()}” already exists.`);
  }
}

export const itServiceService = {
  list: (q: ServiceQuery = {}): Promise<Page<ServiceView>> =>
    simulate(() => {
      const { search, status = 'ALL', criticality = 'ALL', team = 'ALL', sort = 'name', dir = 'asc' } = q;
      const list = all();
      const pols = policies();
      const rows = list
        .filter(
          (s) =>
            includesText([s.name, s.description, s.ownerTeam], search) &&
            (status === 'ALL' || s.status === status) &&
            (criticality === 'ALL' || s.criticality === criticality) &&
            (team === 'ALL' || s.ownerTeam === team)
        )
        .map((s) => view(s, list, pols));
      const m = dir === 'asc' ? 1 : -1;
      rows.sort((a, b) =>
        sort === 'criticality' ? (RANK[a.criticality] - RANK[b.criticality]) * m : a[sort].localeCompare(b[sort]) * m
      );
      return paginate(rows, q.page, q.pageSize);
    }),

  /** Every service, unpaginated: feeds dependency pickers and SLA service selectors. */
  options: () => simulate(() => all(), 120),

  create: (input: ServiceInput) =>
    simulate(() => {
      const errs = validateServiceInput(input);
      if (Object.keys(errs).length) throw new ServiceError('VALIDATION', Object.values(errs)[0]!);
      const list = all();
      const name = input.name.trim().replace(/\s+/g, ' ');
      assertNameFree(list, null, name);
      assertDependencies(list, null, name, input.dependsOn);
      const s: ItService = { id: newId('s'), ...input, name, description: input.description.trim(), status: 'ACTIVE', updatedAt: new Date().toISOString() };
      list.push(s);
      save(list);
      recordAudit('CREATE', 'SERVICE', s.id, `Created IT service “${s.name}” (${s.criticality.toLowerCase()} criticality)`);
      return view(s, list, policies());
    }),

  update: (id: string, input: ServiceInput) =>
    simulate(() => {
      const errs = validateServiceInput(input);
      if (Object.keys(errs).length) throw new ServiceError('VALIDATION', Object.values(errs)[0]!);
      const list = all();
      const idx = list.findIndex((s) => s.id === id);
      if (idx < 0) throw new ServiceError('NOT_FOUND', 'That service no longer exists.');
      const name = input.name.trim().replace(/\s+/g, ' ');
      assertNameFree(list, id, name);
      // Drop references to services that no longer exist (stale data); the form cannot show or untick them.
      input = { ...input, dependsOn: input.dependsOn.filter((d) => list.some((s) => s.id === d)) };
      assertDependencies(list, id, name, input.dependsOn);
      const before = list[idx];
      list[idx] = { ...before, ...input, name, description: input.description.trim(), updatedAt: new Date().toISOString() };
      save(list);
      recordAudit('UPDATE', 'SERVICE', id, `Updated IT service “${name}”`, diffFields(before, list[idx], ['name', 'description', 'ownerTeam', 'criticality', 'dependsOn']));
      return view(list[idx], list, policies());
    }),

  setStatus: (id: string, status: ActiveStatus) =>
    simulate(() => {
      const list = all();
      const idx = list.findIndex((s) => s.id === id);
      if (idx < 0) throw new ServiceError('NOT_FOUND', 'That service no longer exists.');
      const s = list[idx];
      if (status === 'INACTIVE') {
        const dependents = list.filter((x) => x.status === 'ACTIVE' && x.dependsOn.includes(id));
        if (dependents.length) {
          throw new ServiceError('CONFLICT', `${s.name} is a dependency of ${dependents.map((d) => d.name).join(', ')}. Remove it from those services first.`);
        }
      } else {
        const inactive = s.dependsOn.map((d) => list.find((x) => x.id === d)).filter((d) => d && d.status === 'INACTIVE');
        if (inactive.length) {
          throw new ServiceError('CONFLICT', `${s.name} depends on inactive ${inactive.map((d) => d!.name).join(', ')}. Reactivate or remove those dependencies first.`);
        }
      }
      list[idx] = { ...s, status, updatedAt: new Date().toISOString() };
      save(list);
      recordAudit('STATUS_CHANGE', 'SERVICE', id, `${status === 'ACTIVE' ? 'Activated' : 'Deactivated'} IT service “${s.name}”`, diffFields(s, list[idx], ['status']));
      return view(list[idx], list, policies());
    }),
};
