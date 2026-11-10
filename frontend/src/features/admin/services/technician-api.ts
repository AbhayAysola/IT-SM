import type { Page, SortDir, Technician } from '../types';
import { assertPage, call, hasNum, hasStr, isObj } from './http';

const BASE = '/api/admin/technicians';

export type TechnicianSort = 'name' | 'email' | 'maxWorkload' | 'experienceYears' | 'createdAt';
export interface TechnicianQuery {
  search?: string;
  active?: 'true' | 'false' | 'ALL';
  available?: 'true' | 'false' | 'ALL';
  teamLead?: 'true' | 'false' | 'ALL';
  categoryId?: number;
  sort?: TechnicianSort;
  dir?: SortDir;
  page?: number;
  size?: number;
}

export interface SkillInput { categoryId: number; proficiency: number }
export const TECH_LIMITS = { workload: [1, 100], experience: [0, 60], skills: 50, proficiency: [1, 5] } as const;

export interface TechnicianForm {
  userId: string; // create only
  maxWorkload: string;
  experienceYears: string;
  available: boolean;
  isTeamLead: boolean;
  reportsToUserId: string; // '' = none
}

const isTechnician = (x: unknown): x is Technician =>
  isObj(x) && hasNum(x, 'userId') && hasStr(x, 'name') && hasNum(x, 'maxWorkload') && hasNum(x, 'version') &&
  typeof x.active === 'boolean' && typeof x.available === 'boolean' && Array.isArray(x.skills);

const whole = (s: string) => (/^\d+$/.test(s.trim()) ? Number(s.trim()) : NaN);

export function validateTechnician(v: TechnicianForm, mode: 'create' | 'edit', selfId?: number): Record<string, string> {
  const errors: Record<string, string> = {};
  if (mode === 'create' && !(whole(v.userId) >= 1)) errors.userId = 'Choose a technician user.';
  const w = whole(v.maxWorkload);
  if (!(w >= 1 && w <= 100)) errors.maxWorkload = 'Enter a whole number from 1 to 100.';
  const y = whole(v.experienceYears);
  if (!(y >= 0 && y <= 60)) errors.experienceYears = 'Enter a whole number from 0 to 60.';
  if (v.reportsToUserId !== '' && selfId !== undefined && Number(v.reportsToUserId) === selfId) errors.reportsToUserId = 'A technician cannot report to themselves.';
  return errors;
}

export function validateSkills(skills: SkillInput[]): Record<string, string> {
  const errors: Record<string, string> = {};
  if (skills.length > TECH_LIMITS.skills) errors.skills = `At most ${TECH_LIMITS.skills} skills.`;
  const seen = new Set<number>();
  skills.forEach((s, i) => {
    if (!(s.categoryId > 0)) errors[`skills[${i}].categoryId`] = 'Choose a category.';
    else if (seen.has(s.categoryId)) errors[`skills[${i}].categoryId`] = 'Each category can be listed once.';
    seen.add(s.categoryId);
    if (!(Number.isInteger(s.proficiency) && s.proficiency >= 1 && s.proficiency <= 5)) errors[`skills[${i}].proficiency`] = 'Choose 1 to 5.';
  });
  return errors;
}

const detail = (v: TechnicianForm) => ({
  maxWorkload: Number(v.maxWorkload),
  experienceYears: Number(v.experienceYears),
  available: v.available,
  isTeamLead: v.isTeamLead,
  reportsToUserId: v.reportsToUserId === '' ? null : Number(v.reportsToUserId),
});

export const technicianApi = {
  async list(q: TechnicianQuery): Promise<Page<Technician>> {
    const flag = (v?: 'true' | 'false' | 'ALL') => (v === 'ALL' ? undefined : v);
    const body = await call<unknown>(BASE, {
      query: {
        search: q.search, active: flag(q.active), available: flag(q.available), teamLead: flag(q.teamLead), categoryId: q.categoryId,
        sort: q.sort, dir: q.dir, page: q.page ?? 0, size: q.size ?? 10,
      },
    });
    assertPage(body, isTechnician);
    return body;
  },
  get: (userId: number) => call<Technician>(`${BASE}/${userId}`),
  create: (v: TechnicianForm, skills: SkillInput[]) =>
    call<Technician>(BASE, { method: 'POST', body: { userId: Number(v.userId), ...detail(v), skills } }),
  update: (userId: number, v: TechnicianForm, version: number) =>
    call<Technician>(`${BASE}/${userId}`, { method: 'PUT', body: { ...detail(v), version } }),
  updateSkills: (userId: number, skills: SkillInput[], version: number) =>
    call<Technician>(`${BASE}/${userId}/skills`, { method: 'PUT', body: { skills, version } }),
  setActive: (userId: number, active: boolean, version: number) =>
    call<Technician>(`${BASE}/${userId}/${active ? 'activate' : 'deactivate'}`, { method: 'POST', body: { version } }),
};
