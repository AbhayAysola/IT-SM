'use client';

import React, { useRef, useState } from 'react';
import Link from 'next/link';
import { ArrowUpDown, Inbox } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { EmptyState, ErrorState, TableSkeleton } from '@/features/admin/components/states';
import { Pagination } from '@/features/admin/components/pagination';
import { PageHeader } from '@/features/admin/components/page-header';
import { useToast } from '@/features/admin/components/toast';
import { useDebounced, useResource } from '@/features/admin/hooks/use-resource';
import { AssignDialog } from './components/assign-dialog';
import { formatWhen, PriorityBadge, StateBadge } from './components/badges';
import { useActor } from './components/workspace-shell';
import { type IncidentRow, audienceFor, defaultTab, incidentService, INCIDENT_PAGE_SIZE, TABS, type TabId } from './services/incident-service';

const COPY = {
  EMPLOYEE: { title: 'My incidents', description: 'Problems you have reported and where each one stands. We’ll tell you here when we need something from you.' },
  TECHNICIAN: { title: 'My incidents', description: 'Incidents assigned to you. Items that need you first, then by priority.' },
  ADMIN: { title: 'Incidents', description: 'Every incident. Hand out the ones awaiting assignment; the suggestions weigh skill, free capacity and experience.' },
} as const;

const EMPTY: Record<TabId, string> = {
  ACTIVE: 'Nothing active right now.', WAITING: 'Nothing is waiting on a reporter.', DONE: 'No finished incidents yet.',
  UNASSIGNED: 'Every incident has a technician.', OPEN: 'You have no open incidents.',
};

const PRIO_ORDER = { P1: 1, P2: 2, P3: 3, P4: 4 } as const;

/** Wraps the first case-insensitive match of `query` in a <mark>. */
function Highlight({ text, query }: { text: string; query: string }) {
  const q = query.trim();
  if (!q) return <>{text}</>;
  const idx = text.toLowerCase().indexOf(q.toLowerCase());
  if (idx === -1) return <>{text}</>;
  return (
    <>
      {text.slice(0, idx)}
      <mark className="rounded-sm bg-yellow-200 px-0.5 text-yellow-900 dark:bg-yellow-800 dark:text-yellow-100">{text.slice(idx, idx + q.length)}</mark>
      {text.slice(idx + q.length)}
    </>
  );
}

export default function IncidentsPage() {
  const actor = useActor();
  const toast = useToast();
  const audience = audienceFor(actor);
  const tabs = TABS[actor.role];
  const [tab, setTab] = useState<TabId>(defaultTab(actor.role));
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  // The row is kept after closing so the dialog stays mounted (Radix returns focus to the opener on close).
  const [assigning, setAssigning] = useState<IncidentRow | null>(null);
  const [assignOpen, setAssignOpen] = useState(false);
  const [sortPriority, setSortPriority] = useState(false);
  const opener = useRef<HTMLElement | null>(null);
  const debounced = useDebounced(search);

  const { data, loading, error, reload } = useResource(() => incidentService.list(actor, tab, debounced, page), [actor.email, tab, debounced, page]);
  const rawItems = data?.items ?? [];
  const items = sortPriority ? [...rawItems].sort((a, b) => PRIO_ORDER[a.priority] - PRIO_ORDER[b.priority]) : rawItems;
  const copy = COPY[actor.role];

  return (
    <>
      <PageHeader
        title={copy.title}
        description={copy.description}
        actions={
          <Link href="/incidents/new">
            <Button>Report incident</Button>
          </Link>
        }
      />

      <Tabs value={tab} onValueChange={(v) => { setTab(v as TabId); setPage(1); }}>
        <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <TabsList aria-label="Incident groups" className="h-auto flex-wrap justify-start">
            {tabs.map((t) => (
              <TabsTrigger key={t.id} value={t.id}>
                {t.label}
                {data?.counts[t.id] !== undefined && <span className="ml-1.5 text-xs text-muted-foreground">({data.counts[t.id]})</span>}
              </TabsTrigger>
            ))}
          </TabsList>
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant={sortPriority ? 'secondary' : 'outline'}
              size="sm"
              onClick={() => setSortPriority((v) => !v)}
              aria-pressed={sortPriority}
              title="Sort by priority (P1 first)"
            >
              <ArrowUpDown className="h-3.5 w-3.5" aria-hidden="true" />
              Priority
            </Button>
            <div className="relative w-full sm:w-72">
              <Label htmlFor="incident-search" className="sr-only">Search incidents</Label>
              <Input id="incident-search" type="search" value={search} onChange={(e) => { setSearch(e.target.value); setPage(1); }} placeholder="Search by ID, title or person" />
            </div>
          </div>
        </div>

        {tabs.map((t) => (
          <TabsContent key={t.id} value={t.id} className="mt-0">
            {t.id !== tab ? null : (
              <Card>
                {loading && !data ? (
                  <TableSkeleton label="Loading incidents" />
                ) : error ? (
                  <ErrorState message={error} onRetry={reload} />
                ) : items.length === 0 ? (
                  <EmptyState icon={Inbox} title={debounced ? 'No incidents match your search.' : EMPTY[t.id]} />
                ) : (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Incident</TableHead>
                        <TableHead>Priority</TableHead>
                        <TableHead>Status</TableHead>
                        <TableHead>{audience === 'staff' ? 'Reporter' : 'Technician'}</TableHead>
                        {audience === 'staff' && actor.role === 'ADMIN' && <TableHead>Technician</TableHead>}
                        <TableHead>Updated</TableHead>
                        {actor.role === 'ADMIN' && <TableHead><span className="sr-only">Actions</span></TableHead>}
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {items.map((i) => (
                        <TableRow key={i.id}>
                          <TableCell className="min-w-[240px]">
                            <Link href={`/incidents/${i.id}`} className="font-medium text-primary underline-offset-4 hover:underline">
                              <Highlight text={i.title} query={debounced} />
                            </Link>
                            <div className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
                              <span className="font-mono"><Highlight text={i.id} query={debounced} /></span>
                              <span>{i.category}</span>
                              {i.needsAttention && <Badge variant="warning">Needs you</Badge>}
                            </div>
                          </TableCell>
                          <TableCell><PriorityBadge priority={i.priority} /></TableCell>
                          <TableCell><StateBadge state={i.state} audience={audience} /></TableCell>
                          <TableCell className="whitespace-nowrap">
                            <Highlight text={audience === 'staff' ? i.reporterName : (i.assigneeName ?? 'Not assigned yet')} query={debounced} />
                          </TableCell>
                          {audience === 'staff' && actor.role === 'ADMIN' && (
                            <TableCell className="whitespace-nowrap">
                              <Highlight text={i.assigneeName ?? 'Unassigned'} query={debounced} />
                            </TableCell>
                          )}
                          <TableCell className="whitespace-nowrap text-muted-foreground">{formatWhen(i.updatedAt)}</TableCell>
                          {actor.role === 'ADMIN' && (
                            <TableCell className="text-right">
                              {i.state === 'CLASSIFIED' && (
                                <Button size="sm" variant="outline" onClick={(e) => { opener.current = e.currentTarget; setAssigning(i); setAssignOpen(true); }} aria-label={`Assign ${i.id}`}>Assign</Button>
                              )}
                            </TableCell>
                          )}
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                )}
                {data && data.total > INCIDENT_PAGE_SIZE && !error && (
                  <Pagination page={data.page} pageSize={data.pageSize} total={data.total} onPage={setPage} noun="incidents" />
                )}
              </Card>
            )}
          </TabsContent>
        ))}
      </Tabs>

      {assigning && (
        <AssignDialog
          open={assignOpen}
          restoreFocusTo={opener}
          onOpenChange={setAssignOpen}
          actor={actor}
          incident={assigning}
          onAssigned={(name) => { toast.success(`${assigning.id} assigned to ${name}.`); reload(); }}
        />
      )}
    </>
  );
}
