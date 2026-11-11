import { FEEDBACK_STATES, hasPermission, needsReporter } from '../model/lifecycle';
import type { Actor, Incident } from '../model/types';

export const same = (a: string | null | undefined, b: string) => !!a && a.toLowerCase() === b.toLowerCase();

/** True when this person is the one who has to act next. Shared by the mock and the live list. */
export function needsAttention(actor: Actor, i: Pick<Incident, 'state' | 'assigneeEmail' | 'reporterEmail'>, hasFeedback: boolean): boolean {
  if (actor.role === 'ADMIN') return i.state === 'CLASSIFIED' || i.state === 'NEW';
  if (hasPermission(actor.role, 'INCIDENT_WORK') && same(i.assigneeEmail, actor.email)) return i.state === 'ASSIGNED' || i.state === 'ACKNOWLEDGED';
  if (same(i.reporterEmail, actor.email)) return needsReporter(i.state) || (FEEDBACK_STATES.includes(i.state) && !hasFeedback && hasPermission(actor.role, 'INCIDENT_VERIFY_OWN'));
  return false;
}
