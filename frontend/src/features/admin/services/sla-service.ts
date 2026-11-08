import { SEED_SERVICES, SEED_SLA_POLICIES } from '../data/seed-config';
import {
  ActiveStatus, ItService, ListQuery, Page, PRIORITIES, Priority, ServiceError, SlaPolicy, SlaPolicyView,
} from '../types';
import { formatMinutes } from '../format';
import { diffFields, recordAudit } from './audit-service';
import { includesText, loadCollection, newId, paginate, saveCollection, simulate } from './store';

export const MAX_RESOLUTION_MINUTES = 30 * 24 * 60;

export interface SlaQuery extends ListQuery {
  serviceId?: string | 'ALL';
  priority?: Priority | 'ALL';
  status?: ActiveStatus | 'ALL';
  sort?: 'service' | 'priority' | 'responseMinutes' | 'resolutionMinutes';
  dir?: 'asc' | 'desc';
}

export interface SlaInput {
  serviceId: string;
  priority: Priority | '';
  responseMinutes: number;
  resolutionMinutes: number;
}

export function validateSlaInput(i: SlaInput): Partial<Record<keyof SlaInput, string>> {
  const e: Partial<Record<keyof SlaInput, string>> = {};
  if (!i.serviceId) e.serviceId = 'Choose the service this policy covers.';
  if (!i.priority) e.priority = 'Choose a priority.';
  const intOk = (n: number) => Number.isInteger(n) && n > 0;
  if (!intOk(i.responseMinutes)) e.responseMinutes = 'Response target must be a positive whole number.';
  if (!intOk(i.resolutionMinutes)) e.resolutionMinutes = 'Resolution target must be a positive whole number.';
  else if (i.resolutionMinutes > MAX_RESOLUTION_MINUTES) e.resolutionMinutes = 'Resolution target cannot exceed 30 days.';
  if (!e.responseMinutes && !e.resolutionMinutes && i.responseMinutes >= i.resolutionMinutes) {
    e.resolutionMinutes = 'Resolution must take longer than the response target.';
  }
  return e;
}

const all = () => loadCollection<SlaPolicy>('sla', SEED_SLA_POLICIES);
const save = (p: SlaPolicy[]) => saveCollection('sla', p);
const services = () => loadCollection<ItService>('services', SEED_SERVICES);

const view = (p: SlaPolicy, svcs: ItService[]): SlaPolicyView => {
  const s = svcs.find((x) => x.id === p.serviceId);
  return { ...p, serviceName: s?.name ?? 'Unknown service', serviceStatus: s?.status ?? 'INACTIVE' };
};

/** More urgent priorities (P1) must never have looser targets than less urgent ones for the same service. */
function assertConsistentAcrossPriorities(list: SlaPolicy[], candidate: { id: string | null; serviceId: string; priority: Priority; responseMinutes: number; resolutionMinutes: number }) {
  const rank = (p: Priority) => PRIORITIES.indexOf(p);
  for (const o of list) {
    if (o.id === candidate.id || o.serviceId !== candidate.serviceId) continue;
    const moreUrgent = rank(candidate.priority) < rank(o.priority);
    const lessUrgent = rank(candidate.priority) > rank(o.priority);
    const bad = (field: 'responseMinutes' | 'resolutionMinutes', label: string) => {
      if (moreUrgent && candidate[field] > o[field])
        throw new ServiceError('VALIDATION', `${candidate.priority} ${label} (${formatMinutes(candidate[field])}) cannot be longer than the ${o.priority} ${label} (${formatMinutes(o[field])}).`);
      if (lessUrgent && candidate[field] < o[field])
        throw new ServiceError('VALIDATION', `${candidate.priority} ${label} (${formatMinutes(candidate[field])}) cannot be shorter than the ${o.priority} ${label} (${formatMinutes(o[field])}).`);
    };
    bad('responseMinutes', 'response target');
    bad('resolutionMinutes', 'resolution target');
  }
}

export const slaService = {
  list: (q: SlaQuery = {}): Promise<Page<SlaPolicyView>> =>
    simulate(() => {
      const { search, serviceId = 'ALL', priority = 'ALL', status = 'ALL', sort = 'service', dir = 'asc' } = q;
      const svcs = services();
      const rows = all()
        .map((p) => view(p, svcs))
        .filter(
          (p) =>
            includesText([p.serviceName, p.priority], search) &&
            (serviceId === 'ALL' || p.serviceId === serviceId) &&
            (priority === 'ALL' || p.priority === priority) &&
            (status === 'ALL' || p.status === status)
        );
      const m = dir === 'asc' ? 1 : -1;
      rows.sort((a, b) => {
        const tie = PRIORITIES.indexOf(a.priority) - PRIORITIES.indexOf(b.priority);
        if (sort === 'service') return (a.serviceName.localeCompare(b.serviceName) || tie) * m;
        if (sort === 'priority') return (tie || a.serviceName.localeCompare(b.serviceName)) * m;
        return (a[sort] - b[sort]) * m;
      });
      return paginate(rows, q.page, q.pageSize);
    }),

  /** How many active services have no active policy at all. */
  coverageGaps: () =>
    simulate(() => {
      const pols = all().filter((p) => p.status === 'ACTIVE');
      return services().filter((s) => s.status === 'ACTIVE' && !pols.some((p) => p.serviceId === s.id)).map((s) => s.name);
    }, 120),

  create: (input: SlaInput) =>
    simulate(() => {
      const errs = validateSlaInput(input);
      if (Object.keys(errs).length) throw new ServiceError('VALIDATION', Object.values(errs)[0]!);
      const priority = input.priority as Priority;
      const svcs = services();
      const svc = svcs.find((s) => s.id === input.serviceId);
      if (!svc) throw new ServiceError('NOT_FOUND', 'That service no longer exists.');
      if (svc.status !== 'ACTIVE') throw new ServiceError('VALIDATION', `${svc.name} is inactive. Activate it before adding a policy.`);
      const list = all();
      if (list.some((p) => p.serviceId === svc.id && p.priority === priority)) {
        throw new ServiceError('DUPLICATE', `${svc.name} already has a ${priority} policy. Edit the existing one instead.`);
      }
      assertConsistentAcrossPriorities(list, { id: null, serviceId: svc.id, priority, responseMinutes: input.responseMinutes, resolutionMinutes: input.resolutionMinutes });
      const p: SlaPolicy = { id: newId('p'), serviceId: svc.id, priority, responseMinutes: input.responseMinutes, resolutionMinutes: input.resolutionMinutes, status: 'ACTIVE', updatedAt: new Date().toISOString() };
      list.push(p);
      save(list);
      recordAudit('CREATE', 'SLA_POLICY', p.id, `Created ${priority} SLA policy for ${svc.name}: respond in ${formatMinutes(p.responseMinutes)}, resolve in ${formatMinutes(p.resolutionMinutes)}`);
      return view(p, svcs);
    }),

  /** Service and priority identify a policy, so only the targets can change. */
  update: (id: string, input: Pick<SlaInput, 'responseMinutes' | 'resolutionMinutes'>) =>
    simulate(() => {
      const list = all();
      const idx = list.findIndex((p) => p.id === id);
      if (idx < 0) throw new ServiceError('NOT_FOUND', 'That policy no longer exists.');
      const cur = list[idx];
      const errs = validateSlaInput({ serviceId: cur.serviceId, priority: cur.priority, ...input });
      if (Object.keys(errs).length) throw new ServiceError('VALIDATION', Object.values(errs)[0]!);
      assertConsistentAcrossPriorities(list, { id, serviceId: cur.serviceId, priority: cur.priority, ...input });
      list[idx] = { ...cur, ...input, updatedAt: new Date().toISOString() };
      save(list);
      const svcs = services();
      const v = view(list[idx], svcs);
      recordAudit('UPDATE', 'SLA_POLICY', id, `Updated ${v.priority} SLA policy for ${v.serviceName}: respond in ${formatMinutes(v.responseMinutes)}, resolve in ${formatMinutes(v.resolutionMinutes)}`, diffFields(cur, list[idx], ['responseMinutes', 'resolutionMinutes']));
      return v;
    }),

  setStatus: (id: string, status: ActiveStatus) =>
    simulate(() => {
      const list = all();
      const idx = list.findIndex((p) => p.id === id);
      if (idx < 0) throw new ServiceError('NOT_FOUND', 'That policy no longer exists.');
      const before = list[idx];
      list[idx] = { ...before, status, updatedAt: new Date().toISOString() };
      save(list);
      const v = view(list[idx], services());
      recordAudit('STATUS_CHANGE', 'SLA_POLICY', id, `${status === 'ACTIVE' ? 'Enabled' : 'Disabled'} ${v.priority} SLA policy for ${v.serviceName}`, diffFields(before, list[idx], ['status']));
      return v;
    }, 200),

  remove: (id: string) =>
    simulate(() => {
      const list = all();
      const p = list.find((x) => x.id === id);
      if (!p) throw new ServiceError('NOT_FOUND', 'That policy no longer exists.');
      const v = view(p, services());
      save(list.filter((x) => x.id !== id));
      recordAudit('DELETE', 'SLA_POLICY', id, `Deleted ${v.priority} SLA policy for ${v.serviceName}`);
    }),
};
