import type { ActorRole, IncidentState } from '../model/types';

// ---- Tabs ---------------------------------------------------------------------------------------------------------

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
