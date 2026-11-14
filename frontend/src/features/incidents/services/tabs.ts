import type { ActorRole, IncidentState } from '../model/types';

// ---- Tabs ---------------------------------------------------------------------------------------------------------

/** Rows per page on the incident list (the API allows up to 100). */
export const INCIDENT_PAGE_SIZE = 20;

export type TabId = 'ACTIVE' | 'WAITING' | 'DONE' | 'UNASSIGNED' | 'OPEN';

export const TABS: Record<ActorRole, { id: TabId; label: string; states: IncidentState[] }[]> = {
  TECHNICIAN: [
    { id: 'ACTIVE', label: 'Active', states: ['ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS'] },
    { id: 'WAITING', label: 'Waiting on reporter', states: ['PENDING_INFO', 'RESOLVED'] },
    { id: 'DONE', label: 'Done', states: ['VERIFIED', 'CLOSED'] },
  ],
  ADMIN: [
    { id: 'UNASSIGNED', label: 'Awaiting assignment', states: ['NEW', 'CLASSIFIED'] },
    { id: 'ACTIVE', label: 'In progress', states: ['ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO', 'RESOLVED'] },
    { id: 'DONE', label: 'Done', states: ['VERIFIED', 'CLOSED'] },
  ],
  EMPLOYEE: [
    { id: 'OPEN', label: 'Open', states: ['NEW', 'CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO', 'RESOLVED'] },
    { id: 'DONE', label: 'Done', states: ['VERIFIED', 'CLOSED'] },
  ],
};

export const defaultTab = (role: ActorRole): TabId => TABS[role][0].id;

// ---- Filters ------------------------------------------------------------------------------------------------------

export const FILTER_SLA_STATUSES = ['BREACHED', 'WARNING', 'RUNNING', 'PAUSED', 'MET', 'NO_POLICY'] as const;
export type FilterSlaStatus = (typeof FILTER_SLA_STATUSES)[number];

/**
 * Narrowing applied on top of a tab and the text search; every field left unset is "no filter" and they combine with AND.
 * `from` / `to` are `YYYY-MM-DD` days (both inclusive). Mirrors the query parameters of `GET /api/incidents`.
 */
export interface IncidentFilters {
  priority?: 'P1' | 'P2' | 'P3' | 'P4';
  categoryId?: number;
  serviceId?: number;
  /** The technician the incident is assigned to (a user id on the backend). Offered to admins only. */
  assigneeId?: number;
  slaStatus?: FilterSlaStatus;
  from?: string;
  to?: string;
}

export const activeFilterCount = (f: IncidentFilters): number => Object.values(f).filter((v) => v !== undefined && v !== '').length;
