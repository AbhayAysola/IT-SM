import { PRIORITIES, type EffectiveSla, type Page, type Priority, type SlaPolicy, type SortDir } from '../types';
import { assertPage, call, hasNum, isObj } from './http';

const BASE = '/api/admin/sla-policies';

export type SlaSort = 'priority' | 'responseMinutes' | 'resolutionMinutes' | 'updatedAt' | 'createdAt';
export interface SlaQuery {
  search?: string;
  active?: 'true' | 'false' | 'ALL';
  priority?: Priority | 'ALL';
  serviceId?: number;
  global?: boolean;
  sort?: SlaSort;
  dir?: SortDir;
  page?: number;
  size?: number;
}

/** Form values as typed. `serviceId` '' means the global policy of that priority. */
export interface SlaForm { serviceId: string; priority: Priority; responseMinutes: string; resolutionMinutes: string; warningThresholdPct: string }

export const SLA_MAX_RESOLUTION_MINUTES = 30 * 24 * 60;
export const SLA_DEFAULT_WARNING_PCT = 80;

const isPolicy = (x: unknown): x is SlaPolicy =>
  isObj(x) && hasNum(x, 'id') && hasNum(x, 'version') && hasNum(x, 'responseMinutes') && hasNum(x, 'resolutionMinutes') &&
  (PRIORITIES as readonly string[]).includes(x.priority as string) && typeof x.active === 'boolean';

const wholeNumber = (s: string) => (/^\d+$/.test(s.trim()) ? Number(s.trim()) : NaN);

export function validateSla(v: SlaForm, mode: 'create' | 'edit'): Record<string, string> {
  const errors: Record<string, string> = {};
  if (mode === 'create' && !PRIORITIES.includes(v.priority)) errors.priority = 'Choose a priority.';
  const response = wholeNumber(v.responseMinutes);
  const resolution = wholeNumber(v.resolutionMinutes);
  if (!(response >= 1)) errors.responseMinutes = 'Enter a whole number of minutes (1 or more).';
  if (!(resolution >= 1)) errors.resolutionMinutes = 'Enter a whole number of minutes (1 or more).';
  else if (resolution > SLA_MAX_RESOLUTION_MINUTES) errors.resolutionMinutes = `Use at most ${SLA_MAX_RESOLUTION_MINUTES} minutes (30 days).`;
  else if (response >= 1 && response >= resolution) errors.resolutionMinutes = 'Must be longer than the response target.';
  if (v.warningThresholdPct.trim() !== '') {
    const pct = wholeNumber(v.warningThresholdPct);
    if (!(pct >= 1 && pct <= 99)) errors.warningThresholdPct = 'Enter a whole number from 1 to 99.';
  }
  return errors;
}

export const slaCreateBody = (v: SlaForm) => ({
  serviceId: v.serviceId === '' ? null : Number(v.serviceId),
  priority: v.priority,
  responseMinutes: Number(v.responseMinutes),
  resolutionMinutes: Number(v.resolutionMinutes),
  ...(v.warningThresholdPct.trim() === '' ? {} : { warningThresholdPct: Number(v.warningThresholdPct) }),
});

export const slaApi = {
  async list(q: SlaQuery): Promise<Page<SlaPolicy>> {
    const body = await call<unknown>(BASE, {
      query: {
        search: q.search, active: q.active === 'ALL' ? undefined : q.active, priority: q.priority === 'ALL' ? undefined : q.priority,
        serviceId: q.serviceId, global: q.global, sort: q.sort, dir: q.dir, page: q.page ?? 0, size: q.size ?? 10,
      },
    });
    assertPage(body, isPolicy);
    return body;
  },
  get: (id: number) => call<SlaPolicy>(`${BASE}/${id}`),
  /** The provider's answer for a service and priority: service-specific policy, else the global one, else 422. */
  effective: (serviceId: number, priority: Priority) => call<EffectiveSla>(`${BASE}/effective`, { query: { serviceId, priority } }),
  create: (v: SlaForm) => call<SlaPolicy>(BASE, { method: 'POST', body: slaCreateBody(v) }),
  update: (id: number, v: SlaForm, version: number) =>
    call<SlaPolicy>(`${BASE}/${id}`, {
      method: 'PUT',
      body: {
        responseMinutes: Number(v.responseMinutes), resolutionMinutes: Number(v.resolutionMinutes), version,
        ...(v.warningThresholdPct.trim() === '' ? {} : { warningThresholdPct: Number(v.warningThresholdPct) }),
      },
    }),
  setActive: (id: number, active: boolean) => call<SlaPolicy>(`${BASE}/${id}/${active ? 'activate' : 'deactivate'}`, { method: 'POST' }),
};
