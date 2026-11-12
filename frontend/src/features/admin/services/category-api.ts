import type { Category, Page, SortDir } from '../types';
import { assertPage, call, hasNum, hasStr, isObj } from './http';

const BASE = '/api/admin/categories';

export type CategorySort = 'name' | 'code' | 'updatedAt' | 'createdAt';
export interface CategoryQuery {
  search?: string;
  active?: 'true' | 'false' | 'ALL';
  sort?: CategorySort;
  dir?: SortDir;
  page?: number;
  size?: number;
}

/** Form values; `keywords` is the raw comma/newline separated text the user typed. */
export interface CategoryForm { code: string; name: string; description: string; keywords: string }

export const CATEGORY_LIMITS = { name: [3, 60], description: 300, keywords: 15, keyword: [2, 30] } as const;
const CODE = /^[A-Z][A-Z0-9_]{0,39}$/;
const KEYWORD = /^[a-z0-9][a-z0-9 '&./+-]*$/;

const isCategory = (x: unknown): x is Category =>
  isObj(x) && hasNum(x, 'id') && hasStr(x, 'code') && hasStr(x, 'name') && hasNum(x, 'version') && Array.isArray(x.keywords) && typeof x.active === 'boolean';

/** The server's keyword normalisation: trim, lower-case, collapse whitespace. */
export const normalizeKeyword = (k: string) => k.trim().toLowerCase().replace(/\s+/g, ' ');

/** Splits typed text on commas and new lines, normalises, drops empty entries. Duplicates are kept so they can be reported. */
export function parseKeywords(text: string): string[] {
  return text.split(/[,\n]/).map(normalizeKeyword).filter(Boolean);
}

export function validateCategory(v: CategoryForm, mode: 'create' | 'edit'): Record<string, string> {
  const errors: Record<string, string> = {};
  if (mode === 'create') {
    if (!v.code.trim()) errors.code = 'Enter a code.';
    else if (!CODE.test(v.code.trim())) errors.code = 'Use UPPER_SNAKE_CASE: a letter, then letters, digits or _ (max 40).';
  }
  const name = v.name.trim().replace(/\s+/g, ' ');
  if (name.length < 3 || name.length > 60) errors.name = 'Use 3–60 characters.';
  if (v.description.trim().length > 300) errors.description = 'Use at most 300 characters.';
  const keywords = parseKeywords(v.keywords);
  if (keywords.length === 0) errors.keywords = 'Add at least one keyword.';
  else if (keywords.length > 15) errors.keywords = 'Use at most 15 keywords.';
  else {
    const bad = keywords.find((k) => k.length < 2 || k.length > 30);
    const chars = keywords.find((k) => !KEYWORD.test(k));
    const dup = keywords.find((k, i) => keywords.indexOf(k) !== i);
    if (bad) errors.keywords = `“${bad}” must be 2–30 characters.`;
    else if (chars) errors.keywords = `“${chars}” may only contain letters, digits, spaces and ' & . / + -`;
    else if (dup) errors.keywords = `“${dup}” is listed more than once.`;
  }
  return errors;
}

export const toCategoryBody = (v: CategoryForm) => ({
  name: v.name.trim(),
  description: v.description.trim() === '' ? null : v.description.trim(),
  keywords: parseKeywords(v.keywords),
});

export const categoryApi = {
  async list(q: CategoryQuery): Promise<Page<Category>> {
    const body = await call<unknown>(BASE, {
      query: { search: q.search, active: q.active === 'ALL' ? undefined : q.active, sort: q.sort, dir: q.dir, page: q.page ?? 0, size: q.size ?? 10 },
    });
    assertPage(body, isCategory);
    return body;
  },
  get: (id: number) => call<Category>(`${BASE}/${id}`),
  create: (v: CategoryForm) => call<Category>(BASE, { method: 'POST', body: { code: v.code.trim(), ...toCategoryBody(v) } }),
  update: (id: number, v: CategoryForm, version: number) => call<Category>(`${BASE}/${id}`, { method: 'PUT', body: { ...toCategoryBody(v), version } }),
  setActive: (id: number, active: boolean) => call<Category>(`${BASE}/${id}/${active ? 'activate' : 'deactivate'}`, { method: 'POST' }),
};
