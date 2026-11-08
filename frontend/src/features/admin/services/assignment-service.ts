import { getCachedUser } from '@/lib/auth';
import { SEED_ASSIGNMENT } from '../data/seed-assignment';
import { SEED_TECHNICIANS } from '../data/seed';
import {
  ASSIGNMENT_STRATEGIES, ServiceError, TEAMS,
  type AssignmentSettings, type AssignmentSettingsRecord, type AssignmentStrategy, type TechnicianProfile,
} from '../types';
import { diffFlat, recordAudit } from './audit-service';
import { loadCollection, saveCollection, simulate } from './store';

/**
 * CONFIGURATION ONLY. This module stores and validates what an administrator chose. It deliberately contains no
 * routing, round-robin or skill-matching logic and no workload maths: P3 owns those, and will read these settings.
 */

export const STRATEGY_INFO: Record<AssignmentStrategy, { label: string; description: string }> = {
  MANUAL: { label: 'Manual', description: 'A team lead or administrator picks the technician for every incident. Nothing is assigned automatically.' },
  ROUND_ROBIN: { label: 'Round-robin', description: 'Incidents are shared out in turn across a pool of technicians.' },
  SKILL_BASED: { label: 'Skill-based', description: 'Incidents go to technicians whose skills match the incident’s category.' },
};

export const TIE_BREAKER_LABEL = { LOWEST_WORKLOAD: 'Lowest current workload', HIGHEST_SKILL_LEVEL: 'Highest skill level' } as const;
export const POOL_LABEL = { ALL_TECHNICIANS: 'All technicians', BY_TEAM: 'Selected teams only' } as const;
export const MIN_ESCALATION = 5;
export const MAX_ESCALATION = 1440;
export const MAX_RECOMMENDATIONS = 5;

export type SettingsErrors = Partial<Record<
  'strategy' | 'fallbackStrategy' | 'unassignedEscalationMinutes' | 'recommendationCount' | 'teams' | 'minSkillMatchPct', string
>>;

const isInt = (n: unknown): n is number => typeof n === 'number' && Number.isInteger(n);

/** Pure validation. `teamsWithTechnicians` lets callers check a team pool against real profiles. */
export function validateAssignmentSettings(s: AssignmentSettings, teamsWithTechnicians?: Set<string>): SettingsErrors {
  const e: SettingsErrors = {};
  if (!ASSIGNMENT_STRATEGIES.includes(s.strategy)) e.strategy = 'Choose an assignment strategy.';
  if (!isInt(s.unassignedEscalationMinutes) || s.unassignedEscalationMinutes < MIN_ESCALATION || s.unassignedEscalationMinutes > MAX_ESCALATION) {
    e.unassignedEscalationMinutes = `Enter a whole number of minutes from ${MIN_ESCALATION} to ${MAX_ESCALATION}.`;
  }
  if (!isInt(s.recommendationCount) || s.recommendationCount < 0 || s.recommendationCount > MAX_RECOMMENDATIONS) {
    e.recommendationCount = `Enter a whole number from 0 to ${MAX_RECOMMENDATIONS}. Use 0 to turn recommendations off.`;
  }

  if (s.strategy !== 'MANUAL') {
    if (!ASSIGNMENT_STRATEGIES.includes(s.fallbackStrategy)) e.fallbackStrategy = 'Choose what happens when no technician qualifies.';
    else if (s.fallbackStrategy === s.strategy) e.fallbackStrategy = 'The fallback must be different from the selected strategy.';
  }

  if (s.strategy === 'ROUND_ROBIN' && s.roundRobin.pool === 'BY_TEAM') {
    const teams = s.roundRobin.teams;
    if (teams.length === 0) e.teams = 'Select at least one team for the pool.';
    else if (teams.some((t) => !(TEAMS as readonly string[]).includes(t))) e.teams = 'One of the selected teams does not exist.';
    else if (teamsWithTechnicians) {
      const empty = teams.filter((t) => !teamsWithTechnicians.has(t));
      if (empty.length) e.teams = `No technician profiles in ${empty.join(', ')}. Remove ${empty.length === 1 ? 'it' : 'them'} or add technicians first.`;
    }
  }

  if (s.strategy === 'SKILL_BASED') {
    const p = s.skillBased.minSkillMatchPct;
    if (!isInt(p) || p < 1 || p > 100) e.minSkillMatchPct = 'Enter a whole percentage from 1 to 100.';
  }
  return e;
}

/** Human-readable lines describing a configuration, for the summary card and the audit trail. */
export function summarizeAssignmentSettings(s: AssignmentSettings): { headline: string; lines: { label: string; value: string }[] } {
  const info = STRATEGY_INFO[s.strategy];
  const lines: { label: string; value: string }[] = [{ label: 'Strategy', value: info.label }];
  if (s.strategy === 'MANUAL') {
    lines.push({ label: 'Team leads', value: s.manual.notifyTeamLeads ? 'Notified of unassigned incidents' : 'Not notified' });
  } else {
    lines.push({ label: 'If nobody qualifies', value: `Fall back to ${STRATEGY_INFO[s.fallbackStrategy].label.toLowerCase()}` });
  }
  if (s.strategy === 'ROUND_ROBIN') {
    lines.push({ label: 'Pool', value: s.roundRobin.pool === 'BY_TEAM' ? `Teams: ${s.roundRobin.teams.join(', ') || 'none selected'}` : POOL_LABEL.ALL_TECHNICIANS });
    lines.push({ label: 'At full workload', value: s.roundRobin.skipAtCapacity ? 'Skipped' : 'Still eligible' });
  }
  if (s.strategy === 'SKILL_BASED') {
    lines.push({ label: 'Minimum skill match', value: `${s.skillBased.minSkillMatchPct}%` });
    lines.push({ label: 'Minimum skill level', value: s.skillBased.minSkillLevel.charAt(0) + s.skillBased.minSkillLevel.slice(1).toLowerCase() });
    lines.push({ label: 'Tie-breaker', value: TIE_BREAKER_LABEL[s.skillBased.tieBreaker] });
  }
  lines.push({ label: 'Unassigned escalation', value: `After ${s.unassignedEscalationMinutes} min` });
  lines.push({ label: 'Recommendations', value: s.recommendationCount === 0 ? 'Off' : `Top ${s.recommendationCount} shown to assigners` });
  return { headline: info.description, lines };
}

/** Shape check for stored records, so stale data from an older version cannot crash the page. */
function isWellFormed(r: AssignmentSettingsRecord): boolean {
  return ASSIGNMENT_STRATEGIES.includes(r.strategy) && ASSIGNMENT_STRATEGIES.includes(r.fallbackStrategy) &&
    typeof r.unassignedEscalationMinutes === 'number' && typeof r.recommendationCount === 'number' &&
    !!r.manual && typeof r.manual.notifyTeamLeads === 'boolean' &&
    !!r.roundRobin && Array.isArray(r.roundRobin.teams) && typeof r.roundRobin.skipAtCapacity === 'boolean' &&
    !!r.skillBased && typeof r.skillBased.minSkillMatchPct === 'number' && typeof r.skillBased.minSkillLevel === 'string' && typeof r.skillBased.tieBreaker === 'string';
}

const NAME = 'assignment';
const load = () => loadCollection<AssignmentSettingsRecord>(NAME, SEED_ASSIGNMENT);
const strip = ({ updatedAt: _a, updatedBy: _b, ...rest }: AssignmentSettingsRecord): AssignmentSettings => rest;

const teamsWithTechs = () => {
  const profiles = loadCollection<TechnicianProfile>('technicians', SEED_TECHNICIANS);
  return new Set(profiles.map((p) => p.team));
};

export const assignmentService = {
  /** Resolves to null when nothing has been configured yet. */
  get: (): Promise<AssignmentSettingsRecord | null> => simulate(() => {
    const r = load()[0];
    return r && isWellFormed(r) ? r : null; // a corrupt or outdated record is treated as "not configured"
  }),

  /** Which teams currently have technician profiles (for the team pool picker). */
  teamsWithTechnicians: () => simulate(() => Array.from(teamsWithTechs()), 100),

  /**
   * Validates, saves, then appends one audit entry. Throws (and writes nothing) on invalid or unchanged input.
   */
  save: (settings: AssignmentSettings) =>
    simulate(() => {
      const errs = validateAssignmentSettings(settings, teamsWithTechs());
      const first = Object.values(errs)[0];
      if (first) throw new ServiceError('VALIDATION', first);

      const stored = load()[0];
      const prev = stored && isWellFormed(stored) ? stored : null;
      const clean: AssignmentSettings = {
        ...settings,
        roundRobin: { ...settings.roundRobin, teams: settings.roundRobin.teams.slice().sort() },
      };
      const changes = prev ? diffFlat(strip(prev) as unknown as Record<string, unknown>, clean as unknown as Record<string, unknown>) : undefined;
      if (prev && !changes) throw new ServiceError('CONFLICT', 'Nothing has changed, so there is nothing to save.');

      const record: AssignmentSettingsRecord = { ...clean, updatedAt: new Date().toISOString(), updatedBy: getCachedUser()?.name || 'Administrator' };
      saveCollection(NAME, [record]);

      const summary = !prev
        ? `Configured assignment strategy: ${STRATEGY_INFO[clean.strategy].label}`
        : prev.strategy !== clean.strategy
          ? `Changed assignment strategy from ${STRATEGY_INFO[prev.strategy].label} to ${STRATEGY_INFO[clean.strategy].label}`
          : `Updated ${STRATEGY_INFO[clean.strategy].label.toLowerCase()} assignment settings`;
      recordAudit('UPDATE', 'ASSIGNMENT_SETTINGS', 'assignment-settings', summary, changes);
      return record;
    }),
};
