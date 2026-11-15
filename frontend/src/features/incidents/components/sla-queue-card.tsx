'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { Timer } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { ErrorState } from '@/features/admin/components/states';
import { useResource } from '@/features/admin/hooks/use-resource';
import { CardHeading } from './card-heading';
import { PriorityBadge } from './badges';
import { listDeadline } from '../model/sla';
import type { Actor } from '../model/types';
import { incidentService } from '../services/incident-service';

/**
 * P4's SLA queue for staff (GET /api/sla/incidents?unresolved=true): the unresolved incidents closest to, or past, their resolution deadline:
 * a technician's own, or everyone's for an admin. Breached first, then the nearest deadline. "Due in …" is refreshed every
 * 30 seconds without asking the server again.
 */
export function SlaQueueCard({ actor }: { actor: Actor }) {
  const { data, loading, error, reload } = useResource(() => incidentService.slaQueue(actor), [actor.email]);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);

  return (
    <Card data-testid="sla-queue">
      <CardHeader>
        <CardHeading className="flex items-center gap-2"><Timer className="h-4 w-4" aria-hidden="true" /> Service level queue</CardHeading>
        <CardDescription>
          {actor.role === 'ADMIN' ? 'Open incidents' : 'Your open incidents'} closest to their resolution deadline. Breached ones come first.
        </CardDescription>
      </CardHeader>
      <CardContent>
        {loading && !data ? (
          <div role="status" aria-label="Loading the SLA queue" className="space-y-2">
            {[0, 1, 2].map((i) => <Skeleton key={i} className="h-12 w-full" />)}
          </div>
        ) : error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : !data || data.length === 0 ? (
          <p className="text-sm text-muted-foreground">Nothing is running against a deadline right now.</p>
        ) : (
          <ol className="divide-y" aria-label="Incidents by deadline">
            {data.map((item) => {
              const d = listDeadline(item.sla, now);
              return (
                <li key={item.incidentId} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0 last:pb-0">
                  <div className="min-w-0">
                    <Link href={`/incidents/${encodeURIComponent(item.incidentId)}`} className="font-medium text-primary underline-offset-4 hover:underline">
                      {item.title}
                    </Link>
                    <div className="mt-0.5 flex items-center gap-2 text-xs text-muted-foreground">
                      <span className="font-mono">{item.incidentId}</span>
                      <PriorityBadge priority={item.priority} />
                    </div>
                  </div>
                  <div className="flex items-center gap-2 text-sm">
                    <span className="whitespace-nowrap">{d.text}</span>
                    {d.badge && <Badge variant={d.badge.variant}>{d.badge.label}</Badge>}
                  </div>
                </li>
              );
            })}
          </ol>
        )}
        <Link href="/incidents" className="mt-4 inline-block text-sm font-medium text-primary underline-offset-4 hover:underline">
          All incidents, with SLA filters
        </Link>
      </CardContent>
    </Card>
  );
}
