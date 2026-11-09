import { SEED_TECHNICIANS, SEED_USERS } from '../data/seed';
import {
  Availability, ListQuery, ManagedUser, Page, ServiceError, SkillLevel, TechnicianProfile, TechnicianView,
} from '../types';
import { diffFields, recordAudit } from './audit-service';
import { includesText, loadCollection, newId, paginate, saveCollection, simulate } from './store';

export interface TechnicianQuery extends ListQuery {
  availability?: Availability | 'ALL';
  skill?: string | 'ALL';
  team?: string | 'ALL';
  sort?: 'name' | 'workload' | 'skillLevel';
  dir?: 'asc' | 'desc';
}

export interface TechnicianInput {
  skills: string[];
  skillLevel: SkillLevel;
  availability: Availability;
  maxWorkload: number;
  isTeamLead: boolean;
  team: string;
  shift: string;
}

export function validateTechnicianInput(i: TechnicianInput): Partial<Record<keyof TechnicianInput, string>> {
  const e: Partial<Record<keyof TechnicianInput, string>> = {};
  if (i.skills.length === 0) e.skills = 'Select at least one skill so the routing engine can match incidents.';
  if (!Number.isInteger(i.maxWorkload) || i.maxWorkload < 1 || i.maxWorkload > 20) {
    e.maxWorkload = 'Maximum workload must be a whole number from 1 to 20.';
  }
  if (!i.team) e.team = 'Choose a team.';
  if (!i.shift) e.shift = 'Choose a shift.';
  return e;
}

const profiles = () => loadCollection<TechnicianProfile>('technicians', SEED_TECHNICIANS);
const users = () => loadCollection<ManagedUser>('users', SEED_USERS);
const saveProfiles = (p: TechnicianProfile[]) => saveCollection('technicians', p);

const SKILL_RANK: Record<SkillLevel, number> = { JUNIOR: 1, INTERMEDIATE: 2, SENIOR: 3 };

function join(p: TechnicianProfile, us: ManagedUser[]): TechnicianView {
  const u = us.find((x) => x.id === p.userId);
  return { ...p, name: u?.name ?? 'Unknown user', email: u?.email ?? '', accountStatus: u?.status ?? 'SUSPENDED' };
}

/** Used by userService so role changes keep technician profiles consistent. Synchronous, no latency. */
export const technicianInternals = {
  ensureProfile(userId: string) {
    const ps = profiles();
    if (ps.some((p) => p.userId === userId)) return;
    const user = users().find((u) => u.id === userId);
    ps.push({
      id: newId('t'), userId, skills: [], skillLevel: 'JUNIOR', availability: 'AVAILABLE', maxWorkload: 5,
      currentWorkload: 0, isTeamLead: false, team: 'Service Desk', shift: 'Day (08:00-16:00)', updatedAt: new Date().toISOString(),
    });
    saveProfiles(ps);
    if (user) recordAudit('CREATE', 'TECHNICIAN', userId, `Created technician profile for ${user.name}`);
  },
  removeProfileForUser(userId: string, name: string) {
    const ps = profiles();
    const p = ps.find((x) => x.userId === userId);
    if (!p) return;
    if (p.currentWorkload > 0) {
      throw new ServiceError('CONFLICT', `${name} still has ${p.currentWorkload} active incident(s). Reassign them before changing this account.`);
    }
    saveProfiles(ps.filter((x) => x.userId !== userId));
    recordAudit('DELETE', 'TECHNICIAN', p.id, `Removed technician profile for ${name}`);
  },
};

export const technicianService = {
  list: (q: TechnicianQuery = {}): Promise<Page<TechnicianView>> =>
    simulate(() => {
      const { search, availability = 'ALL', skill = 'ALL', team = 'ALL', sort = 'name', dir = 'asc' } = q;
      const us = users();
      const rows = profiles()
        .map((p) => join(p, us))
        .filter(
          (t) =>
            includesText([t.name, t.email, t.team, ...t.skills], search) &&
            (availability === 'ALL' || t.availability === availability) &&
            (skill === 'ALL' || t.skills.includes(skill)) &&
            (team === 'ALL' || t.team === team)
        );
      const m = dir === 'asc' ? 1 : -1;
      rows.sort((a, b) => {
        if (sort === 'workload') return (a.currentWorkload / a.maxWorkload - b.currentWorkload / b.maxWorkload) * m;
        if (sort === 'skillLevel') return (SKILL_RANK[a.skillLevel] - SKILL_RANK[b.skillLevel]) * m;
        return a.name.localeCompare(b.name) * m;
      });
      return paginate(rows, q.page, q.pageSize);
    }),

  /** Technician accounts that have no profile yet (candidates for "Add profile"). */
  unprofiled: () =>
    simulate(() => {
      const have = new Set(profiles().map((p) => p.userId));
      return users().filter((u) => u.role === 'TECHNICIAN' && !have.has(u.id));
    }, 150),

  create: (userId: string, input: TechnicianInput) =>
    simulate(() => {
      const errs = validateTechnicianInput(input);
      if (Object.keys(errs).length) throw new ServiceError('VALIDATION', Object.values(errs)[0]!);
      const user = users().find((u) => u.id === userId && u.role === 'TECHNICIAN');
      if (!user) throw new ServiceError('NOT_FOUND', 'Choose a technician account.');
      const ps = profiles();
      if (ps.some((p) => p.userId === userId)) throw new ServiceError('DUPLICATE', `${user.name} already has a profile.`);
      const p: TechnicianProfile = {
        id: newId('t'), userId, ...input, skills: [...input.skills], currentWorkload: 0, updatedAt: new Date().toISOString(),
      };
      ps.push(p);
      saveProfiles(ps);
      recordAudit('CREATE', 'TECHNICIAN', p.id, `Created technician profile for ${user.name}`);
      return join(p, users());
    }),

  update: (id: string, input: TechnicianInput) =>
    simulate(() => {
      const errs = validateTechnicianInput(input);
      if (Object.keys(errs).length) throw new ServiceError('VALIDATION', Object.values(errs)[0]!);
      const ps = profiles();
      const idx = ps.findIndex((p) => p.id === id);
      if (idx < 0) throw new ServiceError('NOT_FOUND', 'That technician profile no longer exists.');
      const prev = ps[idx];
      if (input.maxWorkload < prev.currentWorkload) {
        throw new ServiceError('CONFLICT', `Maximum workload cannot be below the current workload (${prev.currentWorkload}).`);
      }
      ps[idx] = { ...prev, ...input, skills: [...input.skills], updatedAt: new Date().toISOString() };
      saveProfiles(ps);
      const view = join(ps[idx], users());
      recordAudit('UPDATE', 'TECHNICIAN', id, `Updated technician profile for ${view.name}`, diffFields(prev, ps[idx], ['skills', 'skillLevel', 'availability', 'maxWorkload', 'isTeamLead', 'team', 'shift']));
      return view;
    }),

  setAvailability: (id: string, availability: Availability) =>
    simulate(() => {
      const ps = profiles();
      const idx = ps.findIndex((p) => p.id === id);
      if (idx < 0) throw new ServiceError('NOT_FOUND', 'That technician profile no longer exists.');
      const before = ps[idx];
      ps[idx] = { ...before, availability, updatedAt: new Date().toISOString() };
      saveProfiles(ps);
      const view = join(ps[idx], users());
      recordAudit('STATUS_CHANGE', 'TECHNICIAN', id, `Set ${view.name} to ${availability.replace('_', ' ').toLowerCase()}`, diffFields(before, ps[idx], ['availability']));
      return view;
    }, 200),

  remove: (id: string) =>
    simulate(() => {
      const ps = profiles();
      const p = ps.find((x) => x.id === id);
      if (!p) throw new ServiceError('NOT_FOUND', 'That technician profile no longer exists.');
      const name = join(p, users()).name;
      if (p.currentWorkload > 0) {
        throw new ServiceError('CONFLICT', `${name} still has ${p.currentWorkload} active incident(s). Reassign them before removing the profile.`);
      }
      saveProfiles(ps.filter((x) => x.id !== id));
      recordAudit('DELETE', 'TECHNICIAN', id, `Removed technician profile for ${name}`);
    }),
};
