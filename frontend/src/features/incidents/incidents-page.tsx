'use client';

import React, { useRef, useState } from 'react';
import Link from 'next/link';
import { Inbox } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { EmptyState, ErrorState, TableSkeleton } from '@/features/admin/components/states';
import { PageHeader } from '@/features/admin/components/page-header';
import { useToast } from '@/features/admin/components/toast';
import { useDebounced, useResource } from '@/features/admin/hooks/use-resource';
import { AssignDialog } from './components/assign-dialog';
import { formatWhen, PriorityBadge, StateBadge } from './components/badges';
import { useActor } from './components/workspace-shell';
import { type IncidentRow, audienceFor, defaultTab, incidentService, TABS, type TabId } from './services/incident-service';

const COPY = {
  EMPLOYEE: { title: 'My incidents', description: 'Problems you have reported and where each one stands. We’ll tell you here when we need something from you.' },
  TECHNICIAN: { title: 'My incidents', description: 'Incidents assigned to you. Items that need you first, then by priority.' },
  ADMIN: { title: 'Incidents', description: 'Every incident. Hand out the ones awaiting assignment; the suggestions weigh skill, free capacity and experience.' },
} as const;

const EMPTY: Record<TabId, string> = {
  ACTIVE: 'Nothing active right now.', WAITING: 'Nothing is waiting on a reporter.', DONE: 'No finished incidents yet.',
  UNASSIGNED: 'Every incident has a technician.', OPEN: 'You have no open incidents.',
};

export default function IncidentsPage() {
  const actor = useActor();
  const toast = useToast();
  const audience = audienceFor(actor);
  const tabs = TABS[actor.role];
  const [tab, setTab] = useState<TabId>(defaultTab(actor.role));
  const [search, setSearch] = useState('');
  // The row is kept after closing so the dialog stays mounted (Radix returns focus to the opener on close).
  const [assigning, setAssigning] = useState<IncidentRow | null>(null);
  const [assignOpen, setAssignOpen] = useState(false);
  const opener = useRef<HTMLElement | null>(null);
  const debounced = useDebounced(search);

  const { data, loading, error, reload } = useResource(() => incidentService.list(actor, tab, debounced), [actor.email, tab, debounced]);
  const items = data?.items ?? [];
  const copy = COPY[actor.role];

  return (
    <>
      <PageHeader title={copy.title} description={copy.description} />

      <Tabs value={tab} onValueChange={(v) => setTab(v as TabId)}>
        <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <TabsList aria-label="Incident groups" className="h-auto flex-wrap justify-start">
            {tabs.map((t) => (
              <TabsTrigger key={t.id} value={t.id}>
                {t.label}
                {data?.counts[t.id] !== undefined && <span className="ml-1.5 text-xs text-muted-foreground">({data.counts[t.id]})</span>}
              </TabsTrigger>
            ))}
          </TabsList>
          <div className="relative w-full sm:w-72">
            <Label htmlFor="incident-search" className="sr-only">Search incidents</Label>
            <Input id="incident-search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search by ID, title or person" />
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
                            <Link href={`/incidents/${i.id}`} className="font-medium text-primary underline-offset-4 hover:underline">{i.title}</Link>
                            <div className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-muted-foreground">
                              <span className="font-mono">{i.id}</span>
                              <span>{i.category}</span>
                              {i.needsAttention && <Badge variant="warning">Needs you</Badge>}
                            </div>
                          </TableCell>
                          <TableCell><PriorityBadge priority={i.priority} /></TableCell>
                          <TableCell><StateBadge state={i.state} audience={audience} /></TableCell>
                          <TableCell className="whitespace-nowrap">{audience === 'staff' ? i.reporterName : i.assigneeName ?? 'Not assigned yet'}</TableCell>
                          {audience === 'staff' && actor.role === 'ADMIN' && <TableCell className="whitespace-nowrap">{i.assigneeName ?? 'Unassigned'}</TableCell>}
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
