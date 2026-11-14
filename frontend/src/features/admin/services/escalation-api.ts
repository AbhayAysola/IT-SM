import type { EscalationSettings } from '../types';
import { ServiceError } from '../types';
import { call, hasNum, isObj } from './http';

const BASE = '/api/admin/escalation-settings';

/** The backend's limits (V41 and `EscalationSettings.validate`); checked here first so the form answers instantly. */
export const ESCALATION_LIMITS = { unassignedMinutes: [1, 10080], pct: [101, 1000] } as const;

export interface EscalationForm { unassignedMinutes: string; secondLevelPct: string; adminLevelPct: string }

const whole = (v: string) => (/^\d+$/.test(v.trim()) ? Number(v.trim()) : NaN);

export function validateEscalation(v: EscalationForm): Record<string, string> {
  const errors: Record<string, string> = {};
  const [minU, maxU] = ESCALATION_LIMITS.unassignedMinutes;
  const [minP, maxP] = ESCALATION_LIMITS.pct;
  const u = whole(v.unassignedMinutes), s = whole(v.secondLevelPct), a = whole(v.adminLevelPct);
  if (!(u >= minU && u <= maxU)) errors.unassignedMinutes = `Enter a whole number of minutes from ${minU} to ${maxU} (one week).`;
  if (!(s >= minP && s <= maxP)) errors.secondLevelPct = `Enter a whole percentage from ${minP} to ${maxP}.`;
  if (!(a >= minP && a <= maxP)) errors.adminLevelPct = `Enter a whole percentage from ${minP} to ${maxP}.`;
  else if (!errors.secondLevelPct && a <= s) errors.adminLevelPct = 'Must be higher than the next-level percentage.';
  return errors;
}

function parse(x: unknown): EscalationSettings | null {
  if (!(isObj(x) && hasNum(x, 'unassignedMinutes') && hasNum(x, 'secondLevelPct') && hasNum(x, 'adminLevelPct') && hasNum(x, 'version'))) return null;
  return x as unknown as EscalationSettings;
}

const broken = () => new ServiceError('UNAVAILABLE', 'Something went wrong on our side. Please try again.');

export const escalationApi = {
  async get(): Promise<EscalationSettings> {
    return parse(await call<unknown>(BASE)) ?? Promise.reject(broken());
  },
  async update(v: EscalationForm, version: number): Promise<EscalationSettings> {
    const body = { unassignedMinutes: whole(v.unassignedMinutes), secondLevelPct: whole(v.secondLevelPct), adminLevelPct: whole(v.adminLevelPct), version };
    return parse(await call<unknown>(BASE, { method: 'PUT', body })) ?? Promise.reject(broken());
  },
};
