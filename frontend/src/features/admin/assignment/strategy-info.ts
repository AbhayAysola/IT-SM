/**
 * Plain-language names and descriptions of the assignment strategies P3 registers (`backend/.../assignment/Strategies.kt`).
 * The key stays visible next to the name, so the configuration still matches what the API stores. An unknown key
 * (a strategy added later) falls back to the key itself.
 */
export const STRATEGY_INFO: Record<string, { name: string; summary: string }> = {
  LOAD_BASED: {
    name: 'Load-based',
    summary: 'Picks the technician with the most spare capacity (open incidents against their maximum workload). Ties go to fewer open incidents, then more experience.',
  },
  SKILL_BASED: {
    name: 'Skill-based',
    summary: "Picks the technician most skilled in the incident's category; technicians with no skill in it are never chosen. Ties go to spare capacity, then experience.",
  },
  ROUND_ROBIN: {
    name: 'Round robin',
    summary: 'Takes turns: whoever has waited longest since their last assignment goes next. Ignores skill and load, but still skips anyone unavailable or at capacity.',
  },
  MANUAL: {
    name: 'Manual',
    summary: 'Never assigns on its own. New incidents wait for an administrator, who picks from the ranked recommendations.',
  },
};

/** "Load-based (LOAD_BASED)", or the key alone when it is not one we know. */
export const strategyLabel = (key: string): string => (STRATEGY_INFO[key] ? `${STRATEGY_INFO[key].name} (${key})` : key);
export const strategySummary = (key: string): string | null => STRATEGY_INFO[key]?.summary ?? null;
