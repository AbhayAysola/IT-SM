import type { Priority } from '@/features/admin/types';

export type { Priority };

/**
 * Mirror of the backend `com.itsm.lifecycle` model (P3). The backend is the source of truth: when the real API
 * exists these types come from it. Until then `lifecycle.ts` copies its rules so the UI never offers a move the
 * backend would refuse.
 */
export type IncidentState =
  | 'NEW' | 'CLASSIFIED' | 'ASSIGNED' | 'ACKNOWLEDGED' | 'IN_PROGRESS' | 'PENDING_INFO' | 'RESOLVED' | 'VERIFIED' | 'CLOSED';

export type TransitionName =
  | 'CLASSIFY' | 'ASSIGN' | 'REASSIGN' | 'ACKNOWLEDGE' | 'START_WORK' | 'REQUEST_INFO' | 'PROVIDE_INFO'
  | 'RESOLVE' | 'VERIFY' | 'REOPEN' | 'CLOSE';

export type Permission =
  | 'INCIDENT_CREATE' | 'INCIDENT_VIEW_OWN' | 'INCIDENT_VIEW_ASSIGNED' | 'INCIDENT_VIEW_ALL' | 'INCIDENT_ASSIGN'
  | 'INCIDENT_WORK' | 'INCIDENT_VERIFY_OWN' | 'INCIDENT_CLOSE';

export type ActorRole = 'EMPLOYEE' | 'TECHNICIAN' | 'ADMIN';

/** The signed-in person as the incident services see them. `role` comes from GET /api/users/me. */
export interface Actor {
  /** The user's id from GET /api/users/me. The live API identifies people by id; the mock layer uses the email. */
  id?: number;
  email: string;
  name: string;
  role: ActorRole;
}

export type Audience = 'reporter' | 'staff';

export interface Incident {
  id: string; // INC1024
  title: string;
  description: string;
  /** A skill/category name from the admin skill list, so skill matching needs no mapping table. */
  category: string;
  service: string;
  priority: Priority;
  state: IncidentState;
  reporterEmail: string;
  reporterName: string;
  assigneeEmail: string | null;
  assigneeName: string | null;
  createdAt: string;
  updatedAt: string;
  /** When it last reached RESOLVED; used for "time to resolve" in past-fix suggestions. */
  resolvedAt: string | null;
}

export type Visibility = 'INTERNAL' | 'REPORTER_VISIBLE';

export type EntryType =
  | 'NOTE' | 'FINDING' | 'ACTION_TAKEN' | 'INFO_REQUEST' | 'INFO_RESPONSE' | 'RESOLUTION' | 'REOPEN_REASON' | 'REASSIGNMENT';

/**
 * One row of an incident's audit trail (what the system recorded, as opposed to what people wrote in the investigation).
 * `description` is only present for administrators: the backend gives everyone else the reduced entry.
 */
export interface HistoryEntry {
  id: string;
  occurredAt: string;
  /** Machine code such as `INCIDENT_ASSIGNED`; `history.tsx` turns it into words. */
  action: string;
  actorType: 'USER' | 'SYSTEM';
  /** `ADMIN`, `TECHNICIAN` or `EMPLOYEE` for a person; null for the system. */
  actorRole: string | null;
  previousState: string | null;
  newState: string | null;
  description: string | null;
}

export interface TimelineEntry {
  id: string;
  incidentId: string;
  authorEmail: string;
  authorName: string;
  type: EntryType;
  visibility: Visibility;
  body: string;
  /** The incident's state when this was written. */
  incidentState: IncidentState;
  createdAt: string;
}

export interface Feedback {
  incidentId: string;
  reporterEmail: string;
  rating: number;
  comment: string | null;
  submittedAt: string;
}

export type IncidentErrorCode = 'NOT_FOUND' | 'FORBIDDEN' | 'CONFLICT' | 'VALIDATION' | 'UNAVAILABLE';

/** `fields` carries per-field messages for forms, like the backend's `errors` array. */
export class IncidentError extends Error {
  code: IncidentErrorCode;
  fields?: Record<string, string>;
  constructor(code: IncidentErrorCode, message: string, fields?: Record<string, string>) {
    super(message);
    this.name = 'IncidentError';
    this.code = code;
    this.fields = fields;
  }
}

export interface Recommendation {
  technicianId: number;
  name: string;
  team: string;
  score: number;
  reasons: string[];
}

export interface SimilarResolution {
  incidentId: string;
  title: string;
  /** Null when the source does not know it (the live API does not return it yet). */
  priority: Priority | null;
  sameService: boolean;
  /** The RESOLUTION entry's text. */
  resolution: string;
  resolvedBy: string | null;
  /** Whole days from report to resolution. */
  daysToResolve: number | null;
}

/** SLA status of one clock or of the whole incident (backend: `sla/SlaModels.kt`). `PENDING`: not evaluated yet. */
export type SlaStatus = 'PENDING' | 'NO_POLICY' | 'RUNNING' | 'WARNING' | 'PAUSED' | 'MET' | 'BREACHED';

export interface SlaClockView {
  status: SlaStatus;
  targetMinutes: number | null;
  /** ISO instants; null when unknown (no policy) or not happened. */
  dueAt: string | null;
  metAt: string | null;
  breachedAt: string | null;
}

/**
 * A predicted miss of the resolution deadline (P4's predictive SLA risk, docs/architecture/p4.md section 2): raised once,
 * before the fixed warning, when similar incidents suggest this one will be resolved after its deadline.
 */
export interface SlaRiskView {
  /** ISO instants. */
  raisedAt: string;
  dueAt: string;
  predictedFinishAt: string;
  /** Which past incidents it was based on: same category and priority, or same priority only. */
  basis: 'CATEGORY' | 'PRIORITY';
  sampleSize: number;
  /** 1 when the assignee's load did not change the forecast; 2 means twice their configured capacity. */
  loadFactor: number;
}

/** The response and resolution clocks of one incident (P4, docs/architecture/p4.md section 2). */
export interface SlaSummary {
  status: SlaStatus;
  response: SlaClockView | null;
  resolution: SlaClockView | null;
  /** Set while the resolution clock is paused (waiting on the reporter). */
  pausedSince: string | null;
  /** When the backend last evaluated it (statuses can lag by up to a minute); null for mock data. */
  evaluatedAt: string | null;
  /** Set when the resolution deadline is predicted to be missed; never set by the mock. */
  risk: SlaRiskView | null;
}

/** The SLA facts a list row shows (P4's, carried by P3's incident list). */
export interface RowSla {
  /** Overall status. */
  status: Exclude<SlaStatus, 'PENDING'>;
  resolutionStatus: Exclude<SlaStatus, 'PENDING'>;
  /** ISO instant; null without a policy, or once the incident is finished. */
  resolutionDueAt: string | null;
  /** P4 predicts the resolution deadline will be missed (only while that clock is running or warned). */
  predictedMiss: boolean;
}

/** One escalation of an incident (P4, GET /api/incidents/{id}/escalations; staff only). */
export interface EscalationView {
  /** Why: a breached clock, a resolution far past its target, or nobody assigned in time. */
  description: string;
  tier: 'TEAM_LEAD' | 'NEXT_LEVEL' | 'ADMINS';
  /** Names of the people it went to; empty when nobody qualified. */
  targets: string[];
  createdAt: string;
}
