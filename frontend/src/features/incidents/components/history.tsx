import Link from 'next/link';
import { ArrowRight } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { stateLabel } from '../model/lifecycle';
import type { HistoryEntry, IncidentState } from '../model/types';
import { formatWhen } from './badges';

/** What each audit action means in words. Anything not listed falls back to the code itself, readable but never hidden. */
const ACTION_LABEL: Record<string, string> = {
  INCIDENT_CREATED: 'Reported',
  INCIDENT_CLASSIFIED: 'Classified and prioritised',
  INCIDENT_CATEGORY_SUGGESTED: 'Category suggested by AI',
  INCIDENT_DUPLICATE_FLAGGED: 'Possible duplicates flagged',
  INCIDENT_UPDATED: 'Details edited',
  INCIDENT_ASSIGNED: 'Assigned to a technician',
  INCIDENT_REASSIGNED: 'Reassigned',
  INCIDENT_ACKNOWLEDGED: 'Acknowledged by the technician',
  INCIDENT_STATUS_CHANGED: 'Status changed',
  INCIDENT_INVESTIGATION_ENTRY_ADDED: 'Investigation entry added',
  INCIDENT_RESOLVED: 'Marked resolved',
  INCIDENT_VERIFIED: 'Fix confirmed by the reporter',
  INCIDENT_CLOSED: 'Closed',
  INCIDENT_FEEDBACK_RECORDED: 'Feedback recorded',
  INCIDENT_PRIORITY_CHANGED: 'Priority changed',
  INCIDENT_ESCALATED: 'Escalated',
  PRIORITY_SUGGESTED: 'Priority change suggested',
  PRIORITY_SUGGESTION_DISMISSED: 'Priority suggestion dismissed',
  SLA_STARTED: 'SLA clock started',
  SLA_PAUSED: 'SLA clock paused',
  SLA_RESUMED: 'SLA clock resumed',
  SLA_RECALCULATED: 'SLA deadlines recalculated',
  SLA_WARNING_RAISED: 'SLA warning raised',
  SLA_RISK_PREDICTED: 'Predicted to miss its SLA',
  SLA_BREACHED: 'SLA breached',
  SLA_MET: 'SLA met',
  SLA_POLICY_MISSING: 'No SLA policy applies',
};

const ROLE_LABEL: Record<string, string> = { ADMIN: 'Administrator', TECHNICIAN: 'Technician', EMPLOYEE: 'Reporter' };

export const actionLabel = (code: string): string =>
  ACTION_LABEL[code] ?? code.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());

export const actorLabel = (e: Pick<HistoryEntry, 'actorType' | 'actorRole'>): string =>
  e.actorType === 'SYSTEM' ? 'System' : (e.actorRole && ROLE_LABEL[e.actorRole]) || 'A user';

const asState = (s: string | null) => (s ? stateLabel(s as IncidentState, 'staff') : null);

/** The system's own record of an incident, oldest first. Complements the investigation timeline, which is what people wrote. */
export function History({ entries }: { entries: HistoryEntry[] }) {
  if (entries.length === 0) {
    return <p className="text-sm text-muted-foreground">Nothing has been recorded for this incident yet.</p>;
  }
  return (
    <ol aria-label="Incident history" className="space-y-2.5">
      {entries.map((e) => {
        const from = asState(e.previousState);
        const to = asState(e.newState);
        return (
          <li key={e.id} className="flex gap-3 text-sm">
            <span aria-hidden="true" className="mt-1.5 h-2 w-2 shrink-0 rounded-full bg-primary/60" />
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                <span className="font-medium text-foreground">{actionLabel(e.action)}</span>
                {to && (
                  <Badge variant="outline" className="gap-1 font-normal">
                    {from && (<><span>{from}</span><ArrowRight className="h-3 w-3" aria-label="to" /></>)}
                    <span>{to}</span>
                  </Badge>
                )}
                <span className="ml-auto text-xs text-muted-foreground">
                  <time dateTime={e.occurredAt}>{formatWhen(e.occurredAt)}</time>
                </span>
              </div>
              <p className="text-xs text-muted-foreground">{actorLabel(e)}</p>
              {e.description && <p className="mt-0.5 break-words text-xs text-muted-foreground">{e.description}</p>}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/** Only administrators may read the full audit rows, so only they get the link. */
export function AuditLink({ href }: { href: string }) {
  return (
    <Link href={href} className="mt-3 inline-block text-xs text-primary underline-offset-4 hover:underline">
      Open the full audit entries
    </Link>
  );
}
