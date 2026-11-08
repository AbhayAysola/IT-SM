import type { AssignmentSettingsRecord } from '../types';

/** Starting configuration for a fresh install. */
export const SEED_ASSIGNMENT: AssignmentSettingsRecord[] = [
  {
    strategy: 'SKILL_BASED',
    fallbackStrategy: 'ROUND_ROBIN',
    unassignedEscalationMinutes: 30,
    recommendationCount: 3,
    manual: { notifyTeamLeads: true },
    roundRobin: { pool: 'ALL_TECHNICIANS', teams: [], skipAtCapacity: true },
    skillBased: { minSkillMatchPct: 60, minSkillLevel: 'JUNIOR', tieBreaker: 'LOWEST_WORKLOAD' },
    updatedAt: '2026-10-20T09:15:00.000Z',
    updatedBy: 'Amara Okafor',
  },
];
