import { IncidentError, type Priority, type Recommendation } from './types';

/**
 * Frontend copy of the backend `EligibilityPolicy` and `TechnicianRecommender` (see docs/architecture/p3.md).
 * The admin technician profile only has skill names and one level, not the backend's per-category proficiency 1..5,
 * so `candidatesFrom` derives them (placeholder mapping below). Remove with the mock layer.
 */

export interface Candidate {
  email: string;
  name: string;
  team: string;
  available: boolean;
  maxWorkload: number;
  activeCount: number;
  experienceYears: number;
  /** category name -> proficiency 1..5 */
  skills: Record<string, number>;
}

export const DEFAULT_MAX_WORKLOAD = 5;
export const RECOMMENDATION_LIMIT = 5;

/** Placeholder: the admin form has one level per technician, the backend has one per category. */
export const LEVEL_PROFICIENCY = { JUNIOR: 2, INTERMEDIATE: 3, SENIOR: 5 } as const;
export const LEVEL_YEARS = { JUNIOR: 1, INTERMEDIATE: 4, SENIOR: 8 } as const;

/** A non-positive own limit means "not set" and the default applies (an assumption shared with the backend doc). */
export const effectiveMax = (c: Candidate, defaultMax = DEFAULT_MAX_WORKLOAD) => (c.maxWorkload > 0 ? c.maxWorkload : defaultMax);
export const hasCapacity = (c: Candidate, defaultMax = DEFAULT_MAX_WORKLOAD) => c.activeCount < effectiveMax(c, defaultMax);

export function eligible(candidates: Candidate[], excludeEmails: string[] = [], defaultMax = DEFAULT_MAX_WORKLOAD): Candidate[] {
  const ex = new Set(excludeEmails.map((e) => e.toLowerCase()));
  return candidates.filter((c) => c.available && !ex.has(c.email.toLowerCase()) && hasCapacity(c, defaultMax));
}

/** Validates a person-chosen assignment (skill is deliberately not required, as in the backend). */
export function requireCanTake(c: Candidate, defaultMax = DEFAULT_MAX_WORKLOAD) {
  if (!c.available) throw new IncidentError('CONFLICT', `${c.name} is not available right now.`);
  const max = effectiveMax(c, defaultMax);
  if (c.activeCount >= max) throw new IncidentError('CONFLICT', `${c.name} already has ${c.activeCount} of ${max} allowed active incidents.`);
}

/** Urgent incidents lean on experience, routine ones on free capacity. */
export function weightsFor(priority: Priority) {
  return priority === 'P1' || priority === 'P2'
    ? { skill: 0.45, headroom: 0.2, experience: 0.35 }
    : { skill: 0.4, headroom: 0.45, experience: 0.15 };
}

/** [candidates] must already be eligible. Best first, at most [limit]; ties: higher skill, then name (stable). */
export function recommend(candidates: Candidate[], category: string | null, priority: Priority, limit = RECOMMENDATION_LIMIT): Recommendation[] {
  const w = weightsFor(priority);
  return candidates
    .map((c) => {
      const max = effectiveMax(c);
      const proficiency = category === null ? null : c.skills[category] ?? 0;
      const skill = proficiency === null ? 0.5 : proficiency / 5;
      const headroom = Math.min(1, Math.max(0, 1 - c.activeCount / max));
      const years = Math.max(0, c.experienceYears);
      const experience = Math.min(years, 10) / 10;
      const score = Math.round(100 * (w.skill * skill + w.headroom * headroom + w.experience * experience) * 10) / 10;
      const reasons: string[] = [];
      if (proficiency !== null) reasons.push(proficiency > 0 ? `Skill ${proficiency} of 5 in ${category}` : `No recorded skill in ${category}`);
      reasons.push(`${c.activeCount} of ${max} active incidents`);
      reasons.push(years === 1 ? '1 year of experience' : `${years} years of experience`);
      return { rec: { technicianEmail: c.email, name: c.name, team: c.team, score, reasons } as Recommendation, proficiency: proficiency ?? 0 };
    })
    .sort((a, b) => b.rec.score - a.rec.score || b.proficiency - a.proficiency || a.rec.name.localeCompare(b.rec.name))
    .slice(0, limit)
    .map((x) => x.rec);
}
