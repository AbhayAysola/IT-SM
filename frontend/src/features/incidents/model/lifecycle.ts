import {
  IncidentError, type Actor, type ActorRole, type Audience, type EntryType, type IncidentState, type Permission,
  type TransitionName, type Visibility,
} from './types';

/**
 * Frontend copy of the backend lifecycle rules (`IncidentTransition`, `IncidentStateMachine`, `RolePermissions`,
 * `InvestigationPolicy`). Keep in sync with docs/architecture/p3.md. e2e/incident-model.spec.ts checks
 * the invariants that matter, but only the backend is authoritative: this exists so the mock services and the UI
 * agree with it, and goes away when the real API does.
 */

export const STATES: IncidentState[] = [
  'NEW', 'CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO', 'RESOLVED', 'VERIFIED', 'CLOSED',
];

/** States in which a technician is on the hook; they count toward workload. */
export const WORKLOAD_STATES: IncidentState[] = ['ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO'];

export interface TransitionRule {
  from: IncidentState[];
  to: IncidentState;
  permission: Permission;
  requiresReason: boolean;
}

export const TRANSITIONS: Record<TransitionName, TransitionRule> = {
  CLASSIFY: { from: ['NEW'], to: 'CLASSIFIED', permission: 'INCIDENT_CREATE', requiresReason: false },
  ASSIGN: { from: ['CLASSIFIED'], to: 'ASSIGNED', permission: 'INCIDENT_ASSIGN', requiresReason: false },
  REASSIGN: { from: ['ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO'], to: 'ASSIGNED', permission: 'INCIDENT_ASSIGN', requiresReason: true },
  ACKNOWLEDGE: { from: ['ASSIGNED'], to: 'ACKNOWLEDGED', permission: 'INCIDENT_WORK', requiresReason: false },
  START_WORK: { from: ['ACKNOWLEDGED'], to: 'IN_PROGRESS', permission: 'INCIDENT_WORK', requiresReason: false },
  REQUEST_INFO: { from: ['IN_PROGRESS'], to: 'PENDING_INFO', permission: 'INCIDENT_WORK', requiresReason: true },
  PROVIDE_INFO: { from: ['PENDING_INFO'], to: 'IN_PROGRESS', permission: 'INCIDENT_VIEW_OWN', requiresReason: true },
  RESOLVE: { from: ['IN_PROGRESS'], to: 'RESOLVED', permission: 'INCIDENT_WORK', requiresReason: true },
  VERIFY: { from: ['RESOLVED'], to: 'VERIFIED', permission: 'INCIDENT_VERIFY_OWN', requiresReason: false },
  REOPEN: { from: ['RESOLVED'], to: 'IN_PROGRESS', permission: 'INCIDENT_VERIFY_OWN', requiresReason: true },
  CLOSE: { from: ['VERIFIED'], to: 'CLOSED', permission: 'INCIDENT_CLOSE', requiresReason: false },
};

export const ALL_PERMISSIONS: Permission[] = [
  'INCIDENT_CREATE', 'INCIDENT_VIEW_OWN', 'INCIDENT_VIEW_ASSIGNED', 'INCIDENT_VIEW_ALL', 'INCIDENT_ASSIGN',
  'INCIDENT_WORK', 'INCIDENT_VERIFY_OWN', 'INCIDENT_CLOSE',
];

/**
 * Mirror of backend `RolePermissions`. Note TECHNICIAN has no INCIDENT_VERIFY_OWN: a technician who reports their own
 * incident cannot verify, reopen or rate it. That is a known gap in the backend (docs/architecture/p3.md),
 * reproduced here on purpose so the UI does not promise what the API will refuse.
 */
export const ROLE_PERMISSIONS: Record<ActorRole, Permission[]> = {
  EMPLOYEE: ['INCIDENT_CREATE', 'INCIDENT_VIEW_OWN', 'INCIDENT_VERIFY_OWN'],
  TECHNICIAN: ['INCIDENT_CREATE', 'INCIDENT_VIEW_OWN', 'INCIDENT_VIEW_ASSIGNED', 'INCIDENT_WORK'],
  ADMIN: ALL_PERMISSIONS,
};

export const hasPermission = (role: ActorRole, p: Permission) => ROLE_PERMISSIONS[role].includes(p);

/**
 * The backend still issues role `USER` (treated as EMPLOYEE, like `Role.fromStored`). Anything unrecognised becomes
 * EMPLOYEE too: least privilege, never an accidental upgrade.
 */
export function toActorRole(role: string | undefined | null): ActorRole {
  const r = (role ?? '').trim().toUpperCase();
  return r === 'ADMIN' || r === 'TECHNICIAN' ? r : 'EMPLOYEE';
}

export const MAX_REASON_LENGTH = 1000;

/** What a move needs beyond its state rule: whose incident it is. Admins hold every permission and are exempt. */
export function isOwnerFor(rule: TransitionRule, actor: Actor, incident: { reporterEmail: string; assigneeEmail: string | null }): boolean {
  if (actor.role === 'ADMIN') return true;
  const email = actor.email.toLowerCase();
  switch (rule.permission) {
    case 'INCIDENT_WORK': return incident.assigneeEmail?.toLowerCase() === email;
    case 'INCIDENT_VIEW_OWN':
    case 'INCIDENT_VERIFY_OWN': return incident.reporterEmail.toLowerCase() === email;
    default: return true; // ASSIGN / CLOSE / CLASSIFY are decided by permission alone
  }
}

/** Moves the UI offers for [state] to [actor]. CLASSIFY belongs to intake (P2) and is not offered here. */
export function availableTransitions(
  state: IncidentState, actor: Actor, incident: { reporterEmail: string; assigneeEmail: string | null }
): TransitionName[] {
  return (Object.keys(TRANSITIONS) as TransitionName[]).filter((name) => {
    const rule = TRANSITIONS[name];
    return name !== 'CLASSIFY' && rule.from.includes(state) && hasPermission(actor.role, rule.permission) && isOwnerFor(rule, actor, incident);
  });
}

/**
 * Validates a move the way the backend does, in the same order: legality (409), permission (403), ownership (403),
 * reason (400). Returns the target state and the cleaned reason.
 */
export function applyTransition(
  state: IncidentState, name: TransitionName, actor: Actor,
  incident: { reporterEmail: string; assigneeEmail: string | null }, reason?: string | null
): { to: IncidentState; reason: string | null } {
  const rule = TRANSITIONS[name];
  if (!rule.from.includes(state)) {
    throw new IncidentError('CONFLICT', 'This incident has already moved on. Reload to see its current status.');
  }
  if (!hasPermission(actor.role, rule.permission) || !isOwnerFor(rule, actor, incident)) {
    throw new IncidentError('FORBIDDEN', 'You are not allowed to do that on this incident.');
  }
  const cleaned = reason?.trim() ? reason.trim() : null;
  if (rule.requiresReason && !cleaned) {
    throw new IncidentError('VALIDATION', 'Please write a short explanation first.', { reason: 'Required.' });
  }
  if (cleaned && cleaned.length > MAX_REASON_LENGTH) {
    throw new IncidentError('VALIDATION', `Keep it under ${MAX_REASON_LENGTH} characters.`, { reason: `At most ${MAX_REASON_LENGTH} characters.` });
  }
  return { to: rule.to, reason: cleaned };
}

/** Which timeline entry a move produces (backend `InvestigationPolicy.entryFor`); null when it records nothing. */
export function entryTypeFor(name: TransitionName): EntryType | null {
  switch (name) {
    case 'REQUEST_INFO': return 'INFO_REQUEST';
    case 'PROVIDE_INFO': return 'INFO_RESPONSE';
    case 'RESOLVE': return 'RESOLUTION';
    case 'REOPEN': return 'REOPEN_REASON';
    case 'REASSIGN': return 'REASSIGNMENT';
    default: return null;
  }
}

/** Reassignment notes may discuss colleagues, so they are never shown to the reporter; the conversation always is. */
export const defaultVisibility = (t: EntryType): Visibility => (t === 'REASSIGNMENT' || t === 'NOTE' || t === 'FINDING' || t === 'ACTION_TAKEN' ? 'INTERNAL' : 'REPORTER_VISIBLE');

// ---- Plain-language wording --------------------------------------------------------------------------------------

/** Employee-facing wording avoids jargon (PRODUCT.md principle 3). */
export function stateLabel(state: IncidentState, audience: Audience): string {
  const staff: Record<IncidentState, string> = {
    NEW: 'New', CLASSIFIED: 'Awaiting assignment', ASSIGNED: 'Assigned', ACKNOWLEDGED: 'Acknowledged', IN_PROGRESS: 'In progress',
    PENDING_INFO: 'Waiting for reporter', RESOLVED: 'Resolved, awaiting confirmation', VERIFIED: 'Fix confirmed', CLOSED: 'Closed',
  };
  const reporter: Record<IncidentState, string> = {
    NEW: 'Received', CLASSIFIED: 'Waiting for a technician', ASSIGNED: 'Assigned to a technician', ACKNOWLEDGED: 'Technician has accepted it',
    IN_PROGRESS: 'Being worked on', PENDING_INFO: 'Waiting for your reply', RESOLVED: 'Fixed? Please confirm', VERIFIED: 'Fix confirmed', CLOSED: 'Closed',
  };
  return (audience === 'staff' ? staff : reporter)[state];
}

export const TRANSITION_LABEL: Record<TransitionName, { staff: string; reporter: string }> = {
  CLASSIFY: { staff: 'Classify', reporter: 'Classify' },
  ASSIGN: { staff: 'Assign technician', reporter: 'Assign technician' },
  REASSIGN: { staff: 'Reassign', reporter: 'Reassign' },
  ACKNOWLEDGE: { staff: 'Accept incident', reporter: 'Accept incident' },
  START_WORK: { staff: 'Start investigating', reporter: 'Start investigating' },
  REQUEST_INFO: { staff: 'Ask the reporter a question', reporter: 'Ask the reporter a question' },
  PROVIDE_INFO: { staff: 'Send reply', reporter: 'Send reply' },
  RESOLVE: { staff: 'Mark as resolved', reporter: 'Mark as resolved' },
  VERIFY: { staff: 'Confirm fix', reporter: 'Yes, it’s fixed' },
  REOPEN: { staff: 'Reopen', reporter: 'No, it’s not fixed' },
  CLOSE: { staff: 'Close incident', reporter: 'Close incident' },
};

export const ENTRY_LABEL: Record<EntryType, string> = {
  NOTE: 'Note', FINDING: 'Finding', ACTION_TAKEN: 'Action taken', INFO_REQUEST: 'Question', INFO_RESPONSE: 'Reply',
  RESOLUTION: 'Resolution', REOPEN_REASON: 'Reopened', REASSIGNMENT: 'Reassigned',
};

/** States in which working notes may be added (backend `WORK_STATES`). */
export const NOTE_STATES: IncidentState[] = ['ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO', 'RESOLVED'];

/** Feedback is accepted once the fix is confirmed, and stays open after closing. */
export const FEEDBACK_STATES: IncidentState[] = ['VERIFIED', 'CLOSED'];

/** True when the reporter has to do something next: the list highlights it. */
export const needsReporter = (s: IncidentState) => s === 'PENDING_INFO' || s === 'RESOLVED';
