import { getCachedUser } from '@/lib/auth';
import { SEED_AUDIT } from '../data/seed';
import {
  AUDIT_ACTION_LABEL, AUDIT_ENTITY_LABEL, ServiceError,
  type AuditAction, type AuditEntity, type AuditEntry, type ListQuery, type Page,
} from '../types';
import { includesText, loadCollection, newId, paginate, saveCollection, simulate } from './store';

export interface AuditChanges {
  before: Record<string, string>;
  after: Record<string, string>;
}

const show = (v: unknown): string => {
  if (v === null || v === undefined || v === '') return '—';
  if (Array.isArray(v)) return v.length ? v.join(', ') : '—';
  if (typeof v === 'boolean') return v ? 'Yes' : 'No';
  return String(v);
};

/** Before/after values for only the fields in `keys` that differ. Returns undefined when nothing differs. */
export function diffFields<T extends object>(prev: T, next: T, keys: (keyof T)[]): AuditChanges | undefined {
  const before: Record<string, string> = {};
  const after: Record<string, string> = {};
  for (const k of keys) {
    if (JSON.stringify(prev[k]) !== JSON.stringify(next[k])) {
      before[String(k)] = show(prev[k]);
      after[String(k)] = show(next[k]);
    }
  }
  return Object.keys(after).length ? { before, after } : undefined;
}

/** Same as diffFields for plain nested objects, flattened to dotted keys. */
export function diffFlat(prev: Record<string, unknown>, next: Record<string, unknown>): AuditChanges | undefined {
  const flat = (o: Record<string, unknown>, pre = ''): Record<string, unknown> =>
    Object.entries(o).reduce<Record<string, unknown>>((acc, [k, v]) => {
      if (v && typeof v === 'object' && !Array.isArray(v)) Object.assign(acc, flat(v as Record<string, unknown>, `${pre}${k}.`));
      else acc[`${pre}${k}`] = v;
      return acc;
    }, {});
  const a = flat(prev);
  const b = flat(next);
  return diffFields(a, b, Object.keys(b));
}

/**
 * The only write path. Append-only: entries are only ever added at the front, never edited or removed.
 * Services call this strictly after a mutation has succeeded.
 */
export function recordAudit(action: AuditAction, entity: AuditEntity, entityId: string, summary: string, changes?: AuditChanges) {
  const all = loadCollection<AuditEntry>('audit', SEED_AUDIT);
  all.unshift({
    id: newId('a'),
    at: new Date().toISOString(),
    actor: getCachedUser()?.name || 'Administrator',
    action, entity, entityId, summary,
    ...(changes ? { before: changes.before, after: changes.after } : {}),
  });
  saveCollection('audit', all);
}

export interface AuditQuery extends ListQuery {
  action?: AuditAction | 'ALL';
  entity?: AuditEntity | 'ALL';
  /** Inclusive UTC calendar dates, `YYYY-MM-DD`. */
  from?: string;
  to?: string;
  sort?: 'at' | 'actor' | 'action' | 'entity';
  dir?: 'asc' | 'desc';
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

/** Read-only API for the viewer. There are deliberately no mutating methods here. */
export const auditService = {
  recent: (limit = 8) =>
    simulate(() =>
      loadCollection<AuditEntry>('audit', SEED_AUDIT)
        .slice()
        .sort((a, b) => b.at.localeCompare(a.at))
        .slice(0, limit)
    ),

  list: (q: AuditQuery = {}): Promise<Page<AuditEntry>> =>
    simulate(() => {
      const { search, action = 'ALL', entity = 'ALL', from, to, sort = 'at', dir = 'desc' } = q;
      if ((from && !DATE_RE.test(from)) || (to && !DATE_RE.test(to))) throw new ServiceError('VALIDATION', 'Dates must look like 2026-11-06.');
      if (from && to && from > to) throw new ServiceError('VALIDATION', 'The end date must be on or after the start date.');
      const rows = loadCollection<AuditEntry>('audit', SEED_AUDIT).filter((e) => {
        const day = e.at.slice(0, 10); // ISO strings are UTC
        return (
          includesText([e.actor, e.action, AUDIT_ACTION_LABEL[e.action], e.entity, AUDIT_ENTITY_LABEL[e.entity], e.entityId, e.summary], search) &&
          (action === 'ALL' || e.action === action) &&
          (entity === 'ALL' || e.entity === entity) &&
          (!from || day >= from) &&
          (!to || day <= to)
        );
      });
      const m = dir === 'asc' ? 1 : -1;
      rows.sort((a, b) => {
        if (sort === 'at') return a.at.localeCompare(b.at) * m;
        const av = sort === 'actor' ? a.actor : sort === 'action' ? AUDIT_ACTION_LABEL[a.action] : AUDIT_ENTITY_LABEL[a.entity];
        const bv = sort === 'actor' ? b.actor : sort === 'action' ? AUDIT_ACTION_LABEL[b.action] : AUDIT_ENTITY_LABEL[b.entity];
        return av.localeCompare(bv) * m || b.at.localeCompare(a.at);
      });
      return paginate(rows, q.page, q.pageSize);
    }),

  get: (id: string) =>
    simulate(() => {
      const e = loadCollection<AuditEntry>('audit', SEED_AUDIT).find((x) => x.id === id);
      if (!e) throw new ServiceError('NOT_FOUND', 'That audit entry no longer exists.');
      return e;
    }, 150),
};
