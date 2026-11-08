import { ServiceError } from '../types';

/**
 * Local persistence for the mock admin services. Everything is stored in localStorage under a versioned key,
 * with an in-memory fallback when storage is unavailable. No network, no invented endpoints.
 * Swapping to a real API later means re-implementing the service functions, not touching components.
 */
const VERSION = 'v1';
const memory = new Map<string, string>();

const key = (name: string) => `itsm_admin_${VERSION}_${name}`;

function read(name: string): string | null {
  try {
    return localStorage.getItem(key(name));
  } catch {
    return memory.get(key(name)) ?? null;
  }
}

function write(name: string, value: string) {
  try {
    localStorage.setItem(key(name), value);
  } catch {
    memory.set(key(name), value);
  }
}

export function loadCollection<T>(name: string, seed: T[]): T[] {
  const raw = read(name);
  if (raw) {
    try {
      const parsed: unknown = JSON.parse(raw);
      // Anything that isn't an array of objects is corrupt or from an incompatible version: reseed.
      if (Array.isArray(parsed)) return parsed.filter((x) => x !== null && typeof x === 'object') as T[];
    } catch {
      /* unparseable: fall through and reseed */
    }
  }
  write(name, JSON.stringify(seed));
  return structuredClone(seed);
}

export function saveCollection<T>(name: string, items: T[]) {
  write(name, JSON.stringify(items));
}

/** Clears all admin mock data so seeds are restored on next read. */
export function resetAdminData() {
  for (const n of ['users', 'technicians', 'audit', 'categories', 'services', 'sla', 'assignment']) {
    try {
      localStorage.removeItem(key(n));
    } catch {
      memory.delete(key(n));
    }
  }
}

/** Simulated network latency so loading states are real. */
export const latency = (ms = 350) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * Failure injection for exercising error states: set localStorage `itsm_admin_simulate_error` to "1".
 * (Used by the Playwright suite; harmless otherwise.)
 */
export function failIfSimulated() {
  try {
    if (localStorage.getItem('itsm_admin_simulate_error') === '1') {
      throw new ServiceError('UNAVAILABLE', 'The directory service is unavailable. Please try again.');
    }
  } catch (e) {
    if (e instanceof ServiceError) throw e;
  }
}

export async function simulate<T>(fn: () => T, ms?: number): Promise<T> {
  await latency(ms);
  failIfSimulated();
  return fn();
}

export const newId = (prefix: string) => `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;

export function paginate<T>(all: T[], page = 1, pageSize = 10) {
  const total = all.length;
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const p = Math.min(Math.max(1, page), pages);
  return { items: all.slice((p - 1) * pageSize, p * pageSize), total, page: p, pageSize };
}

export const includesText = (haystack: string[], needle?: string) => {
  const n = (needle ?? '').trim().toLowerCase();
  return !n || haystack.some((h) => h.toLowerCase().includes(n));
};
