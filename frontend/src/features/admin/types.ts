export type AdminRole = 'EMPLOYEE' | 'TECHNICIAN' | 'ADMIN';
export type AccountStatus = 'ACTIVE' | 'SUSPENDED' | 'INVITED';

export interface ManagedUser {
  id: string;
  name: string;
  email: string;
  role: AdminRole;
  department: string;
  status: AccountStatus;
  createdAt: string; // ISO
  lastLoginAt: string | null;
}

export type Availability = 'AVAILABLE' | 'BUSY' | 'OFF_SHIFT' | 'ON_LEAVE';
export type SkillLevel = 'JUNIOR' | 'INTERMEDIATE' | 'SENIOR';

export interface TechnicianProfile {
  id: string;
  /** Links to a ManagedUser with role TECHNICIAN. */
  userId: string;
  skills: string[];
  skillLevel: SkillLevel;
  availability: Availability;
  /** Max incidents the technician may hold at once. */
  maxWorkload: number;
  currentWorkload: number;
  isTeamLead: boolean;
  team: string;
  shift: string;
  updatedAt: string;
}

/** Technician profile joined with its user, for display. */
export interface TechnicianView extends TechnicianProfile {
  name: string;
  email: string;
  accountStatus: AccountStatus;
}

export type AuditAction = 'CREATE' | 'UPDATE' | 'DELETE' | 'STATUS_CHANGE' | 'ROLE_CHANGE';
export type AuditEntity = 'USER' | 'TECHNICIAN' | 'CATEGORY' | 'SERVICE' | 'SLA_POLICY' | 'ASSIGNMENT_SETTINGS';

/** Append-only. The audit viewer (a later batch) reads these; services only ever add. */
export interface AuditEntry {
  id: string;
  at: string;
  actor: string;
  action: AuditAction;
  entity: AuditEntity;
  entityId: string;
  summary: string;
  /** Display-ready field values before/after the change; only the fields that changed. Absent when not recorded. */
  before?: Record<string, string>;
  after?: Record<string, string>;
  /** True for clearly-labelled demo history shipped with the mock data (never written by a real action). */
  seed?: boolean;
}

export const AUDIT_ACTION_LABEL: Record<AuditAction, string> = {
  CREATE: 'Create', UPDATE: 'Update', DELETE: 'Delete', STATUS_CHANGE: 'Status change', ROLE_CHANGE: 'Role change',
};
export const AUDIT_ENTITY_LABEL: Record<AuditEntity, string> = {
  USER: 'User', TECHNICIAN: 'Technician', CATEGORY: 'Category', SERVICE: 'IT service', SLA_POLICY: 'SLA policy', ASSIGNMENT_SETTINGS: 'Assignment settings',
};

export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}

export interface ListQuery {
  search?: string;
  page?: number;
  pageSize?: number;
}

export class ServiceError extends Error {
  code: 'NOT_FOUND' | 'DUPLICATE' | 'VALIDATION' | 'UNAVAILABLE' | 'CONFLICT' | 'FORBIDDEN' | 'UNAUTHENTICATED';
  constructor(code: ServiceError['code'], message: string) {
    super(message);
    this.name = 'ServiceError';
    this.code = code;
  }
}

export const SKILL_OPTIONS = [
  'Networking', 'Hardware', 'Email & Calendar', 'Identity & Access', 'Printers',
  'Windows', 'macOS', 'Linux', 'Database', 'Security', 'VPN & Remote', 'Cloud', 'Audio/Visual', 'Wi-Fi',
] as const;

export const DEPARTMENTS = [
  'Information Technology', 'Finance', 'Human Resources', 'Academic Affairs', 'Facilities',
  'Library', 'Student Services', 'Research Office', 'Procurement',
] as const;

export const TEAMS = ['Service Desk', 'Infrastructure', 'Applications', 'Security'] as const;
export const SHIFTS = ['Day (08:00-16:00)', 'Evening (14:00-22:00)', 'Night (22:00-06:00)'] as const;

// ---- Batch 3: categories, services, SLA policies ----

export type Priority = 'P1' | 'P2' | 'P3' | 'P4';
export const PRIORITIES: Priority[] = ['P1', 'P2', 'P3', 'P4'];
export const PRIORITY_LABEL: Record<Priority, string> = { P1: 'P1 Critical', P2: 'P2 High', P3: 'P3 Medium', P4: 'P4 Low' };

export type ActiveStatus = 'ACTIVE' | 'INACTIVE';

export interface IncidentCategory {
  id: string;
  name: string;
  description: string;
  /** Lower-case phrases the smart-categorisation logic matches against ticket text. */
  keywords: string[];
  defaultPriority: Priority;
  status: ActiveStatus;
  updatedAt: string;
}

export type Criticality = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
export const CRITICALITIES: Criticality[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
export const CRITICALITY_LABEL: Record<Criticality, string> = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' };

export interface ItService {
  id: string;
  name: string;
  description: string;
  ownerTeam: string;
  criticality: Criticality;
  /** Ids of services this one needs in order to work. Must stay acyclic. */
  dependsOn: string[];
  status: ActiveStatus;
  updatedAt: string;
}

export interface ServiceView extends ItService {
  dependencyNames: string[];
  /** Active services that depend on this one. */
  dependentNames: string[];
  policyCount: number;
}

export interface SlaPolicy {
  id: string;
  serviceId: string;
  priority: Priority;
  responseMinutes: number;
  resolutionMinutes: number;
  status: ActiveStatus;
  updatedAt: string;
}

export interface SlaPolicyView extends SlaPolicy {
  serviceName: string;
  serviceStatus: ActiveStatus;
}

// ---- Batch 4: assignment settings (configuration only; P3 owns the algorithms) ----

/**
 * Mock strategy names. The project contracts for P3's routing engine are not available yet, so these names are
 * this frontend's own and only describe configuration. They are not an API contract.
 */
export type AssignmentStrategy = 'MANUAL' | 'ROUND_ROBIN' | 'SKILL_BASED';
export const ASSIGNMENT_STRATEGIES: AssignmentStrategy[] = ['MANUAL', 'ROUND_ROBIN', 'SKILL_BASED'];

export type RoundRobinPool = 'ALL_TECHNICIANS' | 'BY_TEAM';
export type TieBreaker = 'LOWEST_WORKLOAD' | 'HIGHEST_SKILL_LEVEL';

export interface AssignmentSettings {
  strategy: AssignmentStrategy;
  /** Strategy tried when the selected one finds nobody. Not used by MANUAL. */
  fallbackStrategy: AssignmentStrategy;
  /** Common to every strategy. */
  unassignedEscalationMinutes: number;
  /** How many technician recommendations assigners are shown; 0 turns them off. */
  recommendationCount: number;
  manual: { notifyTeamLeads: boolean };
  roundRobin: { pool: RoundRobinPool; teams: string[]; skipAtCapacity: boolean };
  skillBased: { minSkillMatchPct: number; minSkillLevel: SkillLevel; tieBreaker: TieBreaker };
}

export interface AssignmentSettingsRecord extends AssignmentSettings {
  updatedAt: string;
  updatedBy: string;
}
