'use client';

import React from 'react';
import Link from 'next/link';
import { ArrowRight, BellRing, Plus } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { useResource } from '@/features/admin/hooks/use-resource';
import type { User } from '@/types';
import { PriorityBadge, StateBadge } from './components/badges';
import { toActorRole } from './model/lifecycle';
import type { Actor } from './model/types';
import { audienceFor, defaultTab, incidentService, TABS } from './services/incident-service';

const HEADING = {
  EMPLOYEE: { title: 'Your incidents', description: 'Problems you have reported and where they stand.' },
  TECHNICIAN: { title: 'Your queue', description: 'Incidents assigned to you.' },
  ADMIN: { title: 'Incidents at a glance', description: 'Every incident in the organisation.' },
} as const;

/** Words for the tab groups when they are shown as counts ("In progress", not "ACTIVE"). */
const TAB_COUNT_LABEL: Record<string, string> = {
  OPEN: 'Open', ACTIVE: 'Active', WAITING: 'Waiting on the reporter', DONE: 'Finished', UNASSIGNED: 'Awaiting assignment',
};

/**
 * The incident part of the dashboard: how many incidents are in each group, the few that need this person first, and the
 * shortcuts that matter for their role. It reads the same list the Incidents page does (counts per group and the first page of
 * the default group), so the numbers here can never disagree with the page they link to. A failure shows here only: the account
 * card above it never depends on incidents.
 */
export function DashboardSummary({ user }: { user: User }) {
  const actor: Actor = { id: user.id, email: user.email, name: user.name, role: toActorRole(user.role) };
  const tabs = TABS[actor.role];
  const audience = audienceFor(actor);
  const copy = HEADING[actor.role];
  const { data, loading, error, reload } = useResource(
    () => incidentService.list(actor, defaultTab(actor.role), '', 1),
    [actor.email, actor.role]
  );
  const needYou = (data?.items ?? []).filter((i) => i.needsAttention).slice(0, 5);

  return (
    <section aria-labelledby="dashboard-incidents" className="mt-8">
      <div className="mb-3 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 id="dashboard-incidents" className="text-lg font-semibold tracking-tight text-foreground">{copy.title}</h2>
          <p className="text-sm text-muted-foreground">{copy.description}</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Link href="/incidents/new"><Button size="sm"><Plus aria-hidden="true" /> Report an incident</Button></Link>
          <Link href="/incidents"><Button size="sm" variant="outline">All incidents <ArrowRight aria-hidden="true" /></Button></Link>
          {actor.role === 'ADMIN' && (
            <>
              <Link href="/admin/analytics"><Button size="sm" variant="outline">Analytics</Button></Link>
              <Link href="/admin"><Button size="sm" variant="outline">Administration</Button></Link>
            </>
          )}
        </div>
      </div>

      {loading && !data ? (
        <div role="status" aria-label="Loading incidents" className="grid gap-3 sm:grid-cols-3">
          {[0, 1, 2].map((n) => <Skeleton key={n} className="h-20" />)}
        </div>
      ) : error ? (
        <Card>
          <CardContent className="flex flex-wrap items-center justify-between gap-3 p-4 text-sm">
            <span className="text-muted-foreground">Your incidents could not be loaded right now.</span>
            <Button size="sm" variant="outline" onClick={reload}>Try again</Button>
          </CardContent>
        </Card>
      ) : (
        <>
          <ul aria-label="Incidents by group" className="grid gap-3 sm:grid-cols-3">
            {tabs.map((t) => (
              <li key={t.id}>
                <Link href="/incidents" className="block rounded-lg border bg-card p-4 transition-colors hover:bg-muted/50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-ring">
                  <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{TAB_COUNT_LABEL[t.id] ?? t.label}</p>
                  <p className="mt-1 text-3xl font-semibold tabular-nums text-foreground" data-testid={`dash-count-${t.id}`}>{data?.counts[t.id] ?? 0}</p>
                </Link>
              </li>
            ))}
          </ul>

          <Card className="mt-3">
            <CardHeader className="pb-2">
              <CardTitle className="flex items-center gap-2 text-base"><BellRing className="h-4 w-4" aria-hidden="true" /> Needs you first</CardTitle>
              <CardDescription>
                {actor.role === 'EMPLOYEE' ? 'Where we are waiting on you: a question to answer, or a fix to confirm.' : 'Incidents waiting on your next move.'}
              </CardDescription>
            </CardHeader>
            <CardContent>
              {needYou.length === 0 ? (
                <p className="text-sm text-muted-foreground">Nothing is waiting on you right now.</p>
              ) : (
                <ul aria-label="Incidents that need you" className="divide-y">
                  {needYou.map((i) => (
                    <li key={i.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2 text-sm">
                      <Link href={`/incidents/${i.id}`} className="min-w-0 flex-1 truncate font-medium text-primary underline-offset-4 hover:underline">{i.title}</Link>
                      <span className="font-mono text-xs text-muted-foreground">{i.id}</span>
                      <PriorityBadge priority={i.priority} />
                      <StateBadge state={i.state} audience={audience} />
                    </li>
                  ))}
                </ul>
              )}
              {data && data.items.some((i) => i.needsAttention) && needYou.length < data.items.filter((i) => i.needsAttention).length && (
                <Badge variant="secondary" className="mt-2">More on the Incidents page</Badge>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </section>
  );
}
