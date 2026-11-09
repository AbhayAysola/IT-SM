import { SEED_USERS } from '../data/seed';
import {
  AccountStatus, AdminRole, ListQuery, ManagedUser, Page, ServiceError,
} from '../types';
import { diffFields, recordAudit } from './audit-service';
import { includesText, loadCollection, newId, paginate, saveCollection, simulate } from './store';
import { technicianInternals } from './technician-service';

export interface UserQuery extends ListQuery {
  role?: AdminRole | 'ALL';
  status?: AccountStatus | 'ALL';
  department?: string | 'ALL';
  sort?: 'name' | 'createdAt' | 'lastLoginAt';
  dir?: 'asc' | 'desc';
}

export interface UserInput {
  name: string;
  email: string;
  role: AdminRole;
  department: string;
}

export const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;

export function validateUserInput(input: UserInput): Partial<Record<keyof UserInput, string>> {
  const errors: Partial<Record<keyof UserInput, string>> = {};
  if (input.name.trim().length < 2) errors.name = 'Enter the full name (at least 2 characters).';
  if (input.name.trim().length > 80) errors.name = 'Name must be 80 characters or fewer.';
  if (!EMAIL_RE.test(input.email.trim())) errors.email = 'Enter a valid email address, like name@campus.example.';
  if (!input.role) errors.role = 'Choose a role.';
  if (!input.department) errors.department = 'Choose a department.';
  return errors;
}

const all = () => loadCollection<ManagedUser>('users', SEED_USERS);
const save = (u: ManagedUser[]) => saveCollection('users', u);

const activeAdmins = (users: ManagedUser[]) => users.filter((u) => u.role === 'ADMIN' && u.status === 'ACTIVE');

function assertNotLastAdmin(users: ManagedUser[], target: ManagedUser, what: string) {
  const admins = activeAdmins(users);
  if (target.role === 'ADMIN' && target.status === 'ACTIVE' && admins.length <= 1) {
    throw new ServiceError('CONFLICT', `${target.name} is the only active administrator, so you cannot ${what}. Promote another administrator first.`);
  }
}

export const userService = {
  list: (q: UserQuery = {}): Promise<Page<ManagedUser>> =>
    simulate(() => {
      const { search, role = 'ALL', status = 'ALL', department = 'ALL', sort = 'name', dir = 'asc' } = q;
      const rows = all().filter(
        (u) =>
          includesText([u.name, u.email, u.department], search) &&
          (role === 'ALL' || u.role === role) &&
          (status === 'ALL' || u.status === status) &&
          (department === 'ALL' || u.department === department)
      );
      const m = dir === 'asc' ? 1 : -1;
      rows.sort((a, b) => {
        const av = (a[sort] ?? '') as string;
        const bv = (b[sort] ?? '') as string;
        return av.localeCompare(bv, undefined, { sensitivity: 'base' }) * m;
      });
      return paginate(rows, q.page, q.pageSize);
    }),

  get: (id: string) =>
    simulate(() => {
      const u = all().find((x) => x.id === id);
      if (!u) throw new ServiceError('NOT_FOUND', 'That user no longer exists.');
      return u;
    }, 150),

  create: (input: UserInput) =>
    simulate(() => {
      const errs = validateUserInput(input);
      if (Object.keys(errs).length) throw new ServiceError('VALIDATION', Object.values(errs)[0]!);
      const users = all();
      const email = input.email.trim().toLowerCase();
      if (users.some((u) => u.email.toLowerCase() === email)) {
        throw new ServiceError('DUPLICATE', `${email} is already registered.`);
      }
      const user: ManagedUser = {
        id: newId('u'), name: input.name.trim(), email, role: input.role, department: input.department,
        status: 'INVITED', createdAt: new Date().toISOString(), lastLoginAt: null,
      };
      users.push(user);
      save(users);
      if (user.role === 'TECHNICIAN') technicianInternals.ensureProfile(user.id);
      recordAudit('CREATE', 'USER', user.id, `Invited ${user.name} as ${user.role}`);
      return user;
    }),

  update: (id: string, input: UserInput) =>
    simulate(() => {
      const errs = validateUserInput(input);
      if (Object.keys(errs).length) throw new ServiceError('VALIDATION', Object.values(errs)[0]!);
      const users = all();
      const idx = users.findIndex((u) => u.id === id);
      if (idx < 0) throw new ServiceError('NOT_FOUND', 'That user no longer exists.');
      const prev = users[idx];
      const email = input.email.trim().toLowerCase();
      if (users.some((u) => u.id !== id && u.email.toLowerCase() === email)) {
        throw new ServiceError('DUPLICATE', `${email} is already registered.`);
      }
      if (prev.role !== input.role) assertNotLastAdmin(users, prev, 'change their role');
      if (prev.role === 'TECHNICIAN' && input.role !== 'TECHNICIAN') technicianInternals.removeProfileForUser(prev.id, prev.name);

      const next: ManagedUser = { ...prev, name: input.name.trim(), email, role: input.role, department: input.department };
      users[idx] = next;
      save(users);
      if (next.role === 'TECHNICIAN') technicianInternals.ensureProfile(next.id);
      if (prev.role !== next.role) recordAudit('ROLE_CHANGE', 'USER', id, `Changed role of ${next.name} from ${prev.role} to ${next.role}`, diffFields(prev, next, ['name', 'email', 'role', 'department']));
      else recordAudit('UPDATE', 'USER', id, `Updated details for ${next.name}`, diffFields(prev, next, ['name', 'email', 'role', 'department']));
      return next;
    }),

  setStatus: (id: string, status: AccountStatus) =>
    simulate(() => {
      const users = all();
      const idx = users.findIndex((u) => u.id === id);
      if (idx < 0) throw new ServiceError('NOT_FOUND', 'That user no longer exists.');
      const prev = users[idx];
      if (status === 'SUSPENDED') assertNotLastAdmin(users, prev, 'suspend them');
      users[idx] = { ...prev, status };
      save(users);
      recordAudit('STATUS_CHANGE', 'USER', id, `${status === 'SUSPENDED' ? 'Suspended' : 'Reactivated'} ${prev.name}`, diffFields(prev, users[idx], ['status']));
      return users[idx];
    }),

  /** Bulk status change; stops at nothing, returns per-id outcome so the UI can report partial failure. */
  setStatusMany: (ids: string[], status: AccountStatus) =>
    simulate(() => {
      const users = all();
      let changed = 0;
      const skipped: string[] = [];
      for (const id of ids) {
        const idx = users.findIndex((u) => u.id === id);
        if (idx < 0) continue;
        const u = users[idx];
        if (u.status === status) continue;
        if (status === 'SUSPENDED' && u.role === 'ADMIN' && u.status === 'ACTIVE' && activeAdmins(users).length <= 1) {
          skipped.push(u.name);
          continue;
        }
        users[idx] = { ...u, status };
        recordAudit('STATUS_CHANGE', 'USER', id, `${status === 'SUSPENDED' ? 'Suspended' : 'Reactivated'} ${u.name}`, diffFields(u, users[idx], ['status']));
        changed++;
      }
      save(users);
      return { changed, skipped };
    }),

  remove: (id: string) =>
    simulate(() => {
      const users = all();
      const target = users.find((u) => u.id === id);
      if (!target) throw new ServiceError('NOT_FOUND', 'That user no longer exists.');
      assertNotLastAdmin(users, target, 'delete them');
      if (target.role === 'TECHNICIAN') technicianInternals.removeProfileForUser(id, target.name);
      save(users.filter((u) => u.id !== id));
      recordAudit('DELETE', 'USER', id, `Deleted account of ${target.name} (${target.email})`);
    }),
};
