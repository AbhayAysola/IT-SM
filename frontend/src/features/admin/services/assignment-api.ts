import type { AssignmentSettings, StrategyCatalogStatus } from '../types';
import { ServiceError } from '../types';
import { call, hasNum, hasStr, isObj } from './http';

const BASE = '/api/admin/assignment-settings';

export const ASSIGNMENT_LIMITS = { workload: [1, 100], overrides: 100 } as const;
const STRATEGY_KEY = /^[A-Z][A-Z0-9_]{1,49}$/;

export interface AssignmentForm {
  activeStrategy: string;
  defaultMaxWorkload: string;
  overrides: { categoryId: number; strategy: string }[];
}

const STATUSES: readonly string[] = ['AVAILABLE', 'ABSENT', 'FAILED'];

/**
 * Accepts the backend's response, including the additive `strategyCatalogStatus` field, and refuses anything
 * inconsistent: a strategy list may only accompany AVAILABLE, and AVAILABLE must carry a list. A backend that predates the
 * field (it only ever answered null while the catalog was absent) is read as ABSENT / AVAILABLE from the list alone.
 */
function parseSettings(x: unknown): AssignmentSettings | null {
  if (!(isObj(x) && hasStr(x, 'activeStrategy') && hasNum(x, 'defaultMaxWorkload') && hasNum(x, 'version') && Array.isArray(x.categoryOverrides))) return null;
  const list = x.availableStrategies;
  if (!(list === null || (Array.isArray(list) && list.every((k) => typeof k === 'string')))) return null;
  const status = x.strategyCatalogStatus === undefined ? (list === null ? 'ABSENT' : 'AVAILABLE') : x.strategyCatalogStatus;
  if (typeof status !== 'string' || !STATUSES.includes(status)) return null;
  if ((status === 'AVAILABLE') !== Array.isArray(list)) return null;
  return { ...(x as unknown as AssignmentSettings), strategyCatalogStatus: status as StrategyCatalogStatus, availableStrategies: list as string[] | null };
}

/**
 * `registered` is the catalog's list when it is AVAILABLE. A strategy outside it is refused here: a saved key that is no
 * longer registered stays visible but cannot be saved again until it is replaced by a registered one.
 */
export function validateAssignment(v: AssignmentForm, registered?: readonly string[] | null): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!STRATEGY_KEY.test(v.activeStrategy.trim())) errors.activeStrategy = 'Choose a strategy key (UPPER_SNAKE_CASE).';
  else if (registered && !registered.includes(v.activeStrategy.trim())) errors.activeStrategy = `“${v.activeStrategy.trim()}” is not registered. Choose a registered strategy.`;
  const w = /^\d+$/.test(v.defaultMaxWorkload.trim()) ? Number(v.defaultMaxWorkload.trim()) : NaN;
  if (!(w >= 1 && w <= 100)) errors.defaultMaxWorkload = 'Enter a whole number from 1 to 100.';
  if (v.overrides.length > 100) errors.categoryOverrides = 'At most 100 overrides.';
  const seen = new Set<number>();
  v.overrides.forEach((o, i) => {
    if (!STRATEGY_KEY.test(o.strategy.trim())) errors[`categoryOverrides[${i}].strategy`] = 'Choose a strategy key.';
    else if (registered && !registered.includes(o.strategy.trim())) errors[`categoryOverrides[${i}].strategy`] = `“${o.strategy.trim()}” is not registered. Choose a registered strategy.`;
    if (seen.has(o.categoryId)) errors.categoryOverrides = 'A category can only have one override.';
    seen.add(o.categoryId);
  });
  return errors;
}

export const assignmentApi = {
  async get(): Promise<AssignmentSettings> {
    const parsed = parseSettings(await call<unknown>(BASE));
    if (!parsed) throw new ServiceError('UNAVAILABLE', 'Something went wrong on our side. Please try again.');
    return parsed;
  },
  /** Replaces the WHOLE configuration (strategy, default workload and the complete override list). */
  update: async (v: AssignmentForm, version: number): Promise<AssignmentSettings> => {
    const parsed = parseSettings(await call<unknown>(BASE, {
      method: 'PUT',
      body: {
        activeStrategy: v.activeStrategy.trim(),
        defaultMaxWorkload: Number(v.defaultMaxWorkload),
        categoryOverrides: v.overrides.map((o) => ({ categoryId: o.categoryId, strategy: o.strategy.trim() })),
        version,
      },
    }));
    if (!parsed) throw new ServiceError('UNAVAILABLE', 'Something went wrong on our side. Please try again.');
    return parsed;
  },
};
