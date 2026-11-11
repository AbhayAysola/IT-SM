import { IncidentError } from '../model/types';
import type { World } from '../data/seed';

/**
 * Local persistence for the mock incident services: one JSON "world" per signed-in account under a versioned
 * localStorage key, with an in-memory fallback. No network, no invented endpoints. Replacing this layer with the
 * real API means re-implementing `incident-service.ts`; pages and components should not change.
 */
const VERSION = 'v1';
const memory = new Map<string, string>();
const key = (email: string) => `itsm_p3_${VERSION}_world_${email.toLowerCase()}`;

const read = (k: string): string | null => {
  try { return localStorage.getItem(k); } catch { return memory.get(k) ?? null; }
};

const isWorld = (x: unknown): x is World =>
  !!x && typeof x === 'object' && Array.isArray((x as World).incidents) && Array.isArray((x as World).timeline) && Array.isArray((x as World).feedback);

/** Malformed or outdated data is replaced by the seed instead of crashing a page. */
export function loadWorld(email: string, seed: () => World): World {
  const raw = read(key(email));
  if (raw) {
    try {
      const parsed: unknown = JSON.parse(raw);
      if (isWorld(parsed)) return parsed;
    } catch { /* unparseable: reseed */ }
  }
  const fresh = seed();
  saveWorld(email, fresh);
  return structuredClone(fresh);
}

export function saveWorld(email: string, world: World) {
  const s = JSON.stringify(world);
  try { localStorage.setItem(key(email), s); } catch { memory.set(key(email), s); }
}

/** Clears one account's demo data so the seed returns on next read. */
export function resetWorld(email: string) {
  try { localStorage.removeItem(key(email)); } catch { memory.delete(key(email)); }
}

export const latency = (ms = 300) => new Promise<void>((r) => setTimeout(r, ms));

/**
 * Failure injection for exercising error states: set localStorage `itsm_p3_simulate_error` to "1". It can only cause
 * failures, never grant access, and it disappears with the mock layer.
 */
export function failIfSimulated() {
  let on = false;
  try { on = localStorage.getItem('itsm_p3_simulate_error') === '1'; } catch { /* storage unavailable: no injection */ }
  if (on) throw new IncidentError('UNAVAILABLE', 'The incident service is unavailable. Please try again.');
}

export async function simulate<T>(fn: () => T, ms?: number): Promise<T> {
  await latency(ms);
  failIfSimulated();
  return fn();
}
