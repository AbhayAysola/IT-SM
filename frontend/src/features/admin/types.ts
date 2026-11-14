/**
 * Types of the admin area. Everything here mirrors the backend's JSON (see docs/architecture/admin-frontend-contract.md);
 * nothing is a frontend-only invention. Ids are numbers, `version` is the optimistic-lock value, timestamps are ISO strings.
 */

export type AdminRole = 'EMPLOYEE' | 'TECHNICIAN' | 'ADMIN';
export const ADMIN_ROLES: readonly AdminRole[] = ['EMPLOYEE', 'TECHNICIAN', 'ADMIN'];

/** The backend's page envelope: `page` is 0-based. */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export type SortDir = 'asc' | 'desc';

// ---- users (/api/admin/users) ----

export interface AdminUser {
  id: number;
  email: string;
  name: string;
  role: AdminRole;
  active: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

// ---- technicians (/api/admin/technicians; the path id is the USER id) ----

export interface TechnicianSkill {
  categoryId: number;
  categoryCode: string;
  categoryName: string;
  categoryActive: boolean;
  /** 1..5 */
  proficiency: number;
}

export interface Technician {
  userId: number;
  name: string;
  email: string;
  userRole: AdminRole;
  userActive: boolean;
  /** Profile state. Inactive profiles are kept for history. */
  active: boolean;
  /** Set by an administrator (leave, off shift). It is eligibility only: not workload, not "assignable now". */
  available: boolean;
  /** A configured ceiling, not the live workload. */
  maxWorkload: number;
  experienceYears: number;
  isTeamLead: boolean;
  reportsToUserId: number | null;
  skills: TechnicianSkill[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

// ---- incident categories (/api/admin/categories) ----

export interface Category {
  id: number;
  /** Immutable UPPER_SNAKE_CASE identifier. */
  code: string;
  name: string;
  description: string | null;
  /** Normalised and sorted by the server. */
  keywords: string[];
  active: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

// ---- IT services (/api/admin/services) ----

export interface ServiceRef {
  id: number;
  name: string;
  active: boolean;
}

export interface ItService {
  id: number;
  name: string;
  description: string | null;
  /** 1 (low) .. 5 (mission critical) */
  businessCriticality: number;
  ownerTeam: string | null;
  active: boolean;
  dependsOn: ServiceRef[];
  /** Services that directly depend on this one (active and inactive). */
  dependents: ServiceRef[];
  version: number;
  createdAt: string;
  updatedAt: string;
}

export const CRITICALITY_LEVELS = [1, 2, 3, 4, 5] as const;
export const CRITICALITY_LABEL: Record<number, string> = {
  1: '1 · Low', 2: '2 · Minor', 3: '3 · Moderate', 4: '4 · High', 5: '5 · Mission critical',
};

// ---- SLA policies (/api/admin/sla-policies) ----

export type Priority = 'P1' | 'P2' | 'P3' | 'P4';
export const PRIORITIES: readonly Priority[] = ['P1', 'P2', 'P3', 'P4'];
export const PRIORITY_LABEL: Record<Priority, string> = { P1: 'P1 Critical', P2: 'P2 High', P3: 'P3 Medium', P4: 'P4 Low' };

export interface SlaPolicy {
  id: number;
  /** null for the global policy of that priority. */
  service: ServiceRef | null;
  priority: Priority;
  responseMinutes: number;
  resolutionMinutes: number;
  warningThresholdPct: number;
  active: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface EffectiveSla {
  serviceId: number;
  priority: Priority;
  scope: 'SERVICE' | 'GLOBAL';
  policyId: number;
  responseMinutes: number;
  resolutionMinutes: number;
  warningThresholdPct: number;
}

// ---- assignment settings (/api/admin/assignment-settings) ----

export interface AssignmentOverride {
  categoryId: number;
  categoryName: string;
  categoryActive: boolean;
  strategy: string;
}

/**
 * The state of P3's strategy catalog, as the backend reports it (the source of truth for what the page may do):
 *  - AVAILABLE: the catalog answered. `availableStrategies` is its keys, possibly an empty array (nothing registered).
 *  - ABSENT: no catalog is deployed yet. `availableStrategies` is null.
 *  - FAILED: a catalog exists but could not be reached. `availableStrategies` is null: never read that as "no strategies".
 */
export type StrategyCatalogStatus = 'AVAILABLE' | 'ABSENT' | 'FAILED';

/** When SLA escalation fires (P4, GET/PUT /api/admin/escalation-settings). */
export interface EscalationSettings {
  /** An incident nobody picked up goes to the admins this many minutes after it was reported. */
  unassignedMinutes: number;
  /** An open incident goes one level up the reporting line at this % of its resolution target... */
  secondLevelPct: number;
  /** ...and to the admins at this %. Always greater than `secondLevelPct`. */
  adminLevelPct: number;
  version: number;
  updatedAt: string | null;
  updatedByUserId: number | null;
}

export interface AssignmentSettings {
  activeStrategy: string;
  defaultMaxWorkload: number;
  categoryOverrides: AssignmentOverride[];
  strategyCatalogStatus: StrategyCatalogStatus;
  /** Registered strategy keys: an array exactly when `strategyCatalogStatus` is AVAILABLE, otherwise null. Writes are validated server-side. */
  availableStrategies: string[] | null;
  version: number;
  updatedAt: string;
  updatedByUserId: number | null;
}

/**
 * What a failed call turns into. `message` is safe to show: it is either written by the backend for clients
 * (400/404/409/422/503) or one of this client's own fixed texts (401/403/5xx/network). Never raw server internals.
 */
export type ServiceErrorCode =
  | 'NOT_FOUND' | 'DUPLICATE' | 'VALIDATION' | 'UNAVAILABLE' | 'CONFLICT' | 'FORBIDDEN' | 'UNAUTHENTICATED'
  /** 409 CONCURRENT_MODIFICATION: someone else saved first; reload and re-apply. */
  | 'STALE'
  /** 422 domain precondition (e.g. no SLA policy configured). */
  | 'UNPROCESSABLE'
  /** 503: a provider/catalog this operation needs (P3's strategy catalog) is not deployed. */
  | 'DEPENDENCY_UNAVAILABLE';

export class ServiceError extends Error {
  code: ServiceErrorCode;
  status?: number;
  /** Server field name -> message, e.g. `keywords[2]`, `skills[0].proficiency`. */
  fieldErrors: Record<string, string>;
  constructor(code: ServiceErrorCode, message: string, opts: { status?: number; fieldErrors?: Record<string, string> } = {}) {
    super(message);
    this.name = 'ServiceError';
    this.code = code;
    this.status = opts.status;
    this.fieldErrors = opts.fieldErrors ?? {};
  }
}

/** P4's analytics summary (GET /api/admin/analytics/summary). Dates are inclusive UTC days (YYYY-MM-DD). */
export interface SlaCounts {
  tracked: number;
  responseMet: number;
  responseBreached: number;
  responseCompliancePct: number | null;
  resolutionMet: number;
  resolutionBreached: number;
  resolutionCompliancePct: number | null;
}
export interface SlaGroup { id: string | null; name: string; counts: SlaCounts }
export interface DurationStats { count: number; meanMinutes: number | null; p90Minutes: number | null }
export interface AnalyticsSummary {
  from: string;
  to: string;
  incidents: { reported: number; resolved: number; openNow: number; unassignedNow: number; openByState: Record<string, number>; openByPriority: Record<string, number> };
  sla: { overall: SlaCounts; byPriority: SlaGroup[]; byService: SlaGroup[]; byCategory: SlaGroup[] };
  times: { toRespond: DurationStats; toResolve: DurationStats };
  escalations: { total: number; byTrigger: Record<string, number> };
  perDay: { date: string; reported: number; resolved: number; breaches: number; escalations: number }[];
  technicians: { userId: number; name: string; maxWorkload: number; activeIncidents: number; resolved: number; ratings: number; averageRating: number | null }[];
}
