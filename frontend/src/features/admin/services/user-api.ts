import { ADMIN_ROLES, type AdminRole, type AdminUser, type Page, type SortDir } from '../types';
import { assertPage, call, hasNum, hasStr, isObj } from './http';

const BASE = '/api/admin/users';

export type UserSort = 'email' | 'name' | 'role' | 'createdAt' | 'active';
export interface UserQuery {
  search?: string;
  role?: AdminRole | 'ALL';
  active?: 'true' | 'false' | 'ALL';
  sort?: UserSort;
  dir?: SortDir;
  /** 0-based */
  page?: number;
  size?: number;
}

export interface CreateUserInput { email: string; name: string; password: string; role: AdminRole }
export interface EditUserInput { email: string; name: string }

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX = 128;

const isUser = (x: unknown): x is AdminUser =>
  isObj(x) && hasNum(x, 'id') && hasStr(x, 'email') && hasStr(x, 'name') && hasNum(x, 'version') &&
  typeof x.active === 'boolean' && (ADMIN_ROLES as readonly string[]).includes(x.role as string);

function checkEmail(email: string, errors: Record<string, string>) {
  const e = email.trim();
  if (!e) errors.email = 'Enter an email address.';
  else if (e.length > 254 || !EMAIL.test(e)) errors.email = 'Enter a valid email address.';
}
function checkName(name: string, errors: Record<string, string>) {
  const n = name.trim();
  if (!n) errors.name = 'Enter a name.';
  else if (n.length > 100) errors.name = 'Use at most 100 characters.';
}
export function validatePassword(password: string): string | undefined {
  if (password.length < PASSWORD_MIN) return `Use at least ${PASSWORD_MIN} characters.`;
  if (password.length > PASSWORD_MAX) return `Use at most ${PASSWORD_MAX} characters.`;
  return undefined;
}

export function validateCreateUser(v: CreateUserInput): Record<string, string> {
  const errors: Record<string, string> = {};
  checkEmail(v.email, errors);
  checkName(v.name, errors);
  const p = validatePassword(v.password);
  if (p) errors.password = p;
  if (!ADMIN_ROLES.includes(v.role)) errors.role = 'Choose a role.';
  return errors;
}
export function validateEditUser(v: EditUserInput): Record<string, string> {
  const errors: Record<string, string> = {};
  checkEmail(v.email, errors);
  checkName(v.name, errors);
  return errors;
}

export const userApi = {
  async list(q: UserQuery): Promise<Page<AdminUser>> {
    const body = await call<unknown>(BASE, {
      query: {
        search: q.search, role: q.role === 'ALL' ? undefined : q.role, active: q.active === 'ALL' ? undefined : q.active,
        sort: q.sort, dir: q.dir, page: q.page ?? 0, size: q.size ?? 10,
      },
    });
    assertPage(body, isUser);
    return body;
  },
  get: (id: number) => call<AdminUser>(`${BASE}/${id}`),
  create: (v: CreateUserInput) =>
    call<AdminUser>(BASE, { method: 'POST', body: { email: v.email.trim(), name: v.name.trim(), password: v.password, role: v.role } }),
  update: (id: number, v: EditUserInput, version: number) =>
    call<AdminUser>(`${BASE}/${id}`, { method: 'PUT', body: { email: v.email.trim(), name: v.name.trim(), version } }),
  changeRole: (id: number, role: AdminRole, version: number) =>
    call<AdminUser>(`${BASE}/${id}/role`, { method: 'PUT', body: { role, version } }),
  setActive: (id: number, active: boolean, version: number) =>
    call<AdminUser>(`${BASE}/${id}/${active ? 'activate' : 'deactivate'}`, { method: 'POST', body: { version } }),
  resetPassword: (id: number, password: string) =>
    call<AdminUser>(`${BASE}/${id}/reset-password`, { method: 'POST', body: { password } }),
  revokeSessions: (id: number) => call<AdminUser>(`${BASE}/${id}/revoke-sessions`, { method: 'POST' }),
};
