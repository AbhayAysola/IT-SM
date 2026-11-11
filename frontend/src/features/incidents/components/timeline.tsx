import { Badge } from '@/components/ui/badge';
import { ENTRY_LABEL, stateLabel } from '../model/lifecycle';
import type { Audience, TimelineEntry } from '../model/types';
import { formatWhen } from './badges';

/** Oldest first. Internal entries are labelled in words; the service has already removed them for reporters. */
export function Timeline({ entries, audience }: { entries: TimelineEntry[]; audience: Audience }) {
  if (entries.length === 0) {
    return <p className="text-sm text-muted-foreground">Nothing has been written on this incident yet.</p>;
  }
  return (
    <ol aria-label="Investigation timeline" className="space-y-3">
      {entries.map((e) => (
        <li key={e.id} className="rounded-lg border bg-muted/50 p-3">
          <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
            <span className="font-semibold text-foreground">{e.authorName}</span>
            <Badge variant="outline">{ENTRY_LABEL[e.type]}</Badge>
            {e.visibility === 'INTERNAL' && <Badge variant="secondary">Internal, hidden from the reporter</Badge>}
            <span className="ml-auto text-xs text-muted-foreground">
              <time dateTime={e.createdAt}>{formatWhen(e.createdAt)}</time>
            </span>
          </div>
          <p className="mt-2 whitespace-pre-wrap break-words text-sm text-foreground">{e.body}</p>
          <p className="mt-1.5 text-xs text-muted-foreground">While: {stateLabel(e.incidentState, audience)}</p>
        </li>
      ))}
    </ol>
  );
}
