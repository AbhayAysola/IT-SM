import type { ItService, Page, SortDir } from '../types';
import { assertPage, call, hasNum, hasStr, isObj } from './http';

const BASE = '/api/admin/services';

export type ServiceSort = 'name' | 'businessCriticality' | 'updatedAt' | 'createdAt';
export interface ServiceQuery {
  search?: string;
  active?: 'true' | 'false' | 'ALL';
  criticality?: number | 'ALL';
  ownerTeam?: string;
  sort?: ServiceSort;
  dir?: SortDir;
  page?: number;
  size?: number;
}

export interface ServiceForm {
  name: string;
  description: string;
  businessCriticality: number;
  ownerTeam: string;
  dependsOnServiceIds: number[];
}

const isService = (x: unknown): x is ItService =>
  isObj(x) && hasNum(x, 'id') && hasStr(x, 'name') && hasNum(x, 'businessCriticality') && hasNum(x, 'version') &&
  Array.isArray(x.dependsOn) && Array.isArray(x.dependents) && typeof x.active === 'boolean';

export function validateService(v: ServiceForm, selfId?: number): Record<string, string> {
  const errors: Record<string, string> = {};
  const name = v.name.trim().replace(/\s+/g, ' ');
  if (name.length < 3 || name.length > 60) errors.name = 'Use 3–60 characters.';
  if (v.description.trim().length > 300) errors.description = 'Use at most 300 characters.';
  if (v.ownerTeam.trim().length > 100) errors.ownerTeam = 'Use at most 100 characters.';
  if (!Number.isInteger(v.businessCriticality) || v.businessCriticality < 1 || v.businessCriticality > 5) errors.businessCriticality = 'Choose a level from 1 to 5.';
  if (v.dependsOnServiceIds.length > 50) errors.dependsOnServiceIds = 'Select at most 50 dependencies.';
  else if (selfId !== undefined && v.dependsOnServiceIds.includes(selfId)) errors.dependsOnServiceIds = 'A service cannot depend on itself.';
  return errors;
}

export const toServiceBody = (v: ServiceForm) => ({
  name: v.name.trim(),
  description: v.description.trim() === '' ? null : v.description.trim(),
  businessCriticality: v.businessCriticality,
  ownerTeam: v.ownerTeam.trim() === '' ? null : v.ownerTeam.trim(),
  dependsOnServiceIds: v.dependsOnServiceIds,
});

export const itServiceApi = {
  async list(q: ServiceQuery): Promise<Page<ItService>> {
    const body = await call<unknown>(BASE, {
      query: {
        search: q.search, active: q.active === 'ALL' ? undefined : q.active,
        criticality: q.criticality === 'ALL' ? undefined : q.criticality, ownerTeam: q.ownerTeam,
        sort: q.sort, dir: q.dir, page: q.page ?? 0, size: q.size ?? 10,
      },
    });
    assertPage(body, isService);
    return body;
  },
  get: (id: number) => call<ItService>(`${BASE}/${id}`),
  create: (v: ServiceForm) => call<ItService>(BASE, { method: 'POST', body: toServiceBody(v) }),
  update: (id: number, v: ServiceForm, version: number) => call<ItService>(`${BASE}/${id}`, { method: 'PUT', body: { ...toServiceBody(v), version } }),
  setActive: (id: number, active: boolean) => call<ItService>(`${BASE}/${id}/${active ? 'activate' : 'deactivate'}`, { method: 'POST' }),
};
