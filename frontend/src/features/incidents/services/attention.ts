import { FEEDBACK_STATES, hasPermission, needsReporter } from '../model/lifecycle';
import type { Actor, Incident } from '../model/types';

export const same = (a: string | null | undefined, b: string) => !!a && a.toLowerCase() === b.toLowerCase();

type People = Pick<Incident, 'assigneeEmail' | 'reporterEmail' | 'assigneeId' | 'reporterId'>;
const sameId = (a: number | null | undefined, b: number | undefined) => a != null && b !== undefined && a === b;
/** Ids when both sides have one (the live API), emails otherwise (the mock layer). */
export const isReporter = (actor: Actor, i: Partial<People>) => i.reporterId != null && actor.id !== undefined ? sameId(i.reporterId, actor.id) : same(i.reporterEmail, actor.email);
export const isAssignee = (actor: Actor, i: Partial<People>) => i.assigneeId != null && actor.id !== undefined ? sameId(i.assigneeId, actor.id) : same(i.assigneeEmail, actor.email);

/** True when this person is the one who has to act next. Shared by the mock and the live list. */
export function needsAttention(actor: Actor, i: Pick<Incident, 'state'> & Partial<People>, hasFeedback: boolean): boolean {
  if (actor.role === 'ADMIN') return i.state === 'CLASSIFIED' || i.state === 'NEW';
  if (hasPermission(actor.role, 'INCIDENT_WORK') && isAssignee(actor, i)) return i.state === 'ASSIGNED' || i.state === 'ACKNOWLEDGED';
  if (isReporter(actor, i)) return needsReporter(i.state) || (FEEDBACK_STATES.includes(i.state) && !hasFeedback && hasPermission(actor.role, 'INCIDENT_VERIFY_OWN'));
  return false;
}
