import type { ActorRole, IncidentState, Priority } from './types';

/**
 * Changing an incident's priority after intake (P2's `POST /api/incidents/{id}/priority`, docs/architecture/p4.md
 * section 1). Pure, so the rules are tested without pages; the backend enforces the same ones.
 */

/** Classified and not yet resolved: still waiting for or getting work. */
export const PRIORITY_CHANGEABLE_STATES: IncidentState[] = ['CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO'];

/** Same limit as the backend. */
export const MAX_PRIORITY_REASON = 500;

export const PRIORITIES: Priority[] = ['P1', 'P2', 'P3', 'P4'];

export const PRIORITY_NAME: Record<Priority, string> = { P1: 'P1 Critical', P2: 'P2 High', P3: 'P3 Medium', P4: 'P4 Low' };

/** Admins (who triage) may change it while the incident is being worked on. */
export const canChangePriority = (role: ActorRole, state: IncidentState) => role === 'ADMIN' && PRIORITY_CHANGEABLE_STATES.includes(state);

/** Field errors for a proposed change, or null when it may be sent. */
export function priorityChangeErrors(current: Priority, next: Priority | null, reason: string): Record<string, string> | null {
  const errors: Record<string, string> = {};
  if (!next) errors.priority = 'Choose the new priority.';
  else if (next === current) errors.priority = `It is already ${PRIORITY_NAME[current]}. Choose a different priority.`;
  const r = reason.trim();
  if (!r) errors.reason = 'Say why the priority changes.';
  else if (r.length > MAX_PRIORITY_REASON) errors.reason = `Keep it under ${MAX_PRIORITY_REASON} characters.`;
  return Object.keys(errors).length ? errors : null;
}

/** "raised" or "lowered", for messages. P1 is the highest. */
export const direction = (from: Priority, to: Priority) => (PRIORITIES.indexOf(to) < PRIORITIES.indexOf(from) ? 'raised' : 'lowered');

// ---- suggestions (P4's priority monitor) -------------------------------------------------------------------------

/** P4 suggests a different priority for an open incident (admins only; GET /api/incidents/{id}/priority-suggestion). */
export interface PrioritySuggestionView {
  currentPriority: Priority;
  suggestedPriority: Priority;
  rules: ('CRITICALITY' | 'DUPLICATES')[];
  /** Plain-language reasons, shown as they are. */
  reasons: string[];
  createdAt: string;
}

/** "Raise to P1 Critical" or "Lower to P4 Low". */
export const suggestionHeadline = (s: PrioritySuggestionView) =>
  `${direction(s.currentPriority, s.suggestedPriority) === 'raised' ? 'Raise' : 'Lower'} to ${PRIORITY_NAME[s.suggestedPriority]}`;

/** The reason prefilled when an admin applies a suggestion (they can edit it); within the backend's limit. */
export const suggestionReason = (s: PrioritySuggestionView) =>
  `Applied the suggested priority: ${s.reasons.join(' ')}`.slice(0, MAX_PRIORITY_REASON);
