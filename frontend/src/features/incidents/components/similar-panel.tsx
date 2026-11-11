import { Lightbulb } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card';
import { CardHeading } from './card-heading';
import type { SimilarResolution } from '../model/types';

/**
 * "Past fixes for similar incidents" for staff on an active incident: closed incidents in the same category (same
 * service first). Suggestions only: read the fix, check it applies, do not assume it does.
 */
export function SimilarPanel({ items }: { items: SimilarResolution[] }) {
  return (
    <Card>
      <CardHeader>
        <CardHeading className="flex items-center gap-2"><Lightbulb className="h-4 w-4" aria-hidden="true" /> Past fixes for similar incidents</CardHeading>
        <CardDescription>Closed incidents in the same category. Check that a fix applies before reusing it.</CardDescription>
      </CardHeader>
      <CardContent>
        {items.length === 0 ? (
          <p className="text-sm text-muted-foreground">No similar incidents have been resolved yet.</p>
        ) : (
          <ul className="space-y-3">
            {items.map((s) => (
              <li key={s.incidentId} className="rounded-lg border bg-muted/50 p-3 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-xs">{s.incidentId}</span>
                  {s.priority && <Badge variant="outline">{s.priority}</Badge>}
                  {s.sameService && <Badge variant="secondary">Same service</Badge>}
                </div>
                <p className="mt-1 font-medium">{s.title}</p>
                <p className="mt-1 whitespace-pre-wrap break-words text-muted-foreground">{s.resolution}</p>
                {s.resolvedBy && s.daysToResolve !== null && (
                  <p className="mt-1.5 text-xs text-muted-foreground">
                    Fixed by {s.resolvedBy} in {s.daysToResolve === 0 ? 'under a day' : s.daysToResolve === 1 ? '1 day' : `${s.daysToResolve} days`}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
