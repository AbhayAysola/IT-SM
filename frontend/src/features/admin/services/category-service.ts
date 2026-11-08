import { SEED_CATEGORIES } from '../data/seed-config';
import { ActiveStatus, IncidentCategory, ListQuery, Page, Priority, ServiceError } from '../types';
import { diffFields, recordAudit } from './audit-service';
import { includesText, loadCollection, newId, paginate, saveCollection, simulate } from './store';

export interface CategoryQuery extends ListQuery {
  status?: ActiveStatus | 'ALL';
  priority?: Priority | 'ALL';
  sort?: 'name' | 'updatedAt';
  dir?: 'asc' | 'desc';
}

export interface CategoryInput {
  name: string;
  description: string;
  keywords: string[];
  defaultPriority: Priority;
}

export const MAX_KEYWORDS = 15;

export const normalizeKeyword = (k: string) => k.trim().toLowerCase().replace(/\s+/g, ' ');

/** Returns an error string for one keyword, or null when it is acceptable on its own. */
export function validateKeyword(k: string): string | null {
  const n = normalizeKeyword(k);
  if (n.length < 2) return 'Keywords need at least 2 characters.';
  if (n.length > 30) return 'Keywords can be at most 30 characters.';
  if (!/^[A-Za-z0-9][A-Za-z0-9 '&./+-]*$/.test(n)) return 'Keywords may only contain letters, numbers, spaces and - . / + & \'';
  return null;
}

export function validateCategoryInput(i: CategoryInput): Partial<Record<keyof CategoryInput, string>> {
  const e: Partial<Record<keyof CategoryInput, string>> = {};
  const name = i.name.trim();
  if (name.length < 3) e.name = 'Enter a category name (at least 3 characters).';
  else if (name.length > 60) e.name = 'Name must be 60 characters or fewer.';
  const d = i.description.trim();
  if (d.length < 10) e.description = 'Describe what belongs in this category (at least 10 characters).';
  else if (d.length > 300) e.description = 'Description must be 300 characters or fewer.';
  if (i.keywords.length === 0) e.keywords = 'Add at least one keyword so tickets can be matched to this category.';
  else if (i.keywords.length > MAX_KEYWORDS) e.keywords = `Use at most ${MAX_KEYWORDS} keywords.`;
  else if (new Set(i.keywords.map(normalizeKeyword)).size !== i.keywords.length) e.keywords = 'Remove duplicate keywords.';
  else {
    const bad = i.keywords.map(validateKeyword).find(Boolean);
    if (bad) e.keywords = bad;
  }
  return e;
}

const all = () => loadCollection<IncidentCategory>('categories', SEED_CATEGORIES);
const save = (c: IncidentCategory[]) => saveCollection('categories', c);

/** Two active categories sharing a keyword would make automatic categorisation ambiguous. */
function assertKeywordsFree(list: IncidentCategory[], selfId: string | null, keywords: string[]) {
  for (const other of list) {
    if (other.id === selfId || other.status !== 'ACTIVE') continue;
    const clash = keywords.find((k) => other.keywords.includes(k));
    if (clash) throw new ServiceError('CONFLICT', `The keyword “${clash}” is already used by the active category “${other.name}”. Keywords must be unique across active categories.`);
  }
}

function assertNameFree(list: IncidentCategory[], selfId: string | null, name: string) {
  if (list.some((c) => c.id !== selfId && c.name.trim().toLowerCase() === name.trim().toLowerCase())) {
    throw new ServiceError('DUPLICATE', `A category named “${name.trim()}” already exists.`);
  }
}

function clean(i: CategoryInput): CategoryInput {
  return { ...i, name: i.name.trim().replace(/\s+/g, ' '), description: i.description.trim(), keywords: i.keywords.map(normalizeKeyword) };
}

export const categoryService = {
  list: (q: CategoryQuery = {}): Promise<Page<IncidentCategory>> =>
    simulate(() => {
      const { search, status = 'ALL', priority = 'ALL', sort = 'name', dir = 'asc' } = q;
      const rows = all().filter(
        (c) =>
          includesText([c.name, c.description, ...c.keywords], search) &&
          (status === 'ALL' || c.status === status) &&
          (priority === 'ALL' || c.defaultPriority === priority)
      );
      const m = dir === 'asc' ? 1 : -1;
      rows.sort((a, b) => a[sort].localeCompare(b[sort]) * m);
      return paginate(rows, q.page, q.pageSize);
    }),

  create: (input: CategoryInput) =>
    simulate(() => {
      const errs = validateCategoryInput(input);
      if (Object.keys(errs).length) throw new ServiceError('VALIDATION', Object.values(errs)[0]!);
      const v = clean(input);
      const list = all();
      assertNameFree(list, null, v.name);
      assertKeywordsFree(list, null, v.keywords);
      const c: IncidentCategory = { id: newId('c'), ...v, status: 'ACTIVE', updatedAt: new Date().toISOString() };
      list.push(c);
      save(list);
      recordAudit('CREATE', 'CATEGORY', c.id, `Created incident category “${c.name}”`);
      return c;
    }),

  update: (id: string, input: CategoryInput) =>
    simulate(() => {
      const errs = validateCategoryInput(input);
      if (Object.keys(errs).length) throw new ServiceError('VALIDATION', Object.values(errs)[0]!);
      const v = clean(input);
      const list = all();
      const idx = list.findIndex((c) => c.id === id);
      if (idx < 0) throw new ServiceError('NOT_FOUND', 'That category no longer exists.');
      assertNameFree(list, id, v.name);
      if (list[idx].status === 'ACTIVE') assertKeywordsFree(list, id, v.keywords);
      const before = list[idx];
      list[idx] = { ...before, ...v, updatedAt: new Date().toISOString() };
      save(list);
      recordAudit('UPDATE', 'CATEGORY', id, `Updated incident category “${v.name}”`, diffFields(before, list[idx], ['name', 'description', 'keywords', 'defaultPriority']));
      return list[idx];
    }),

  setStatus: (id: string, status: ActiveStatus) =>
    simulate(() => {
      const list = all();
      const idx = list.findIndex((c) => c.id === id);
      if (idx < 0) throw new ServiceError('NOT_FOUND', 'That category no longer exists.');
      if (status === 'ACTIVE') assertKeywordsFree(list, id, list[idx].keywords);
      if (status === 'INACTIVE' && list.filter((c) => c.status === 'ACTIVE').length <= 1) {
        throw new ServiceError('CONFLICT', 'At least one category must stay active so new incidents can be classified.');
      }
      const before = list[idx];
      list[idx] = { ...before, status, updatedAt: new Date().toISOString() };
      save(list);
      recordAudit('STATUS_CHANGE', 'CATEGORY', id, `${status === 'ACTIVE' ? 'Activated' : 'Deactivated'} incident category “${list[idx].name}”`, diffFields(before, list[idx], ['status']));
      return list[idx];
    }),
};
