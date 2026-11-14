'use client';

import React, { useRef, useState } from 'react';
import Link from 'next/link';
import { ArrowUpDown, CalendarClock, Inbox } from 'lucide-react';
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
import { ClearFilters, FilterSelect } from '@/features/admin/components/filters';
import { AssignDialog } from './components/assign-dialog';
import { formatWhen, PriorityBadge, StateBadge } from './components/badges';
import { useActor } from './components/workspace-shell';
import {
  activeFilterCount, type FilterSlaStatus, type IncidentFilters, type IncidentRow, type IncidentSort, audienceFor, defaultTab, FILTER_SLA_STATUSES, incidentService,
  INCIDENT_PAGE_SIZE, type SlaAttention, TABS, type TabId,
} from './services/incident-service';
import { listDeadline } from './model/sla';

const COPY = {
  EMPLOYEE: { title: 'My incidents', description: 'Problems you have reported and where each one stands. We’ll tell you here when we need something from you.' },
  TECHNICIAN: { title: 'My incidents', description: 'Incidents assigned to you. Items that need you first, then by priority.' },
  ADMIN: { title: 'Incidents', description: 'Every incident. Hand out the ones awaiting assignment; the suggestions weigh skill, free capacity and experience.' },
} as const;

const EMPTY: Record<TabId, string> = {
  ACTIVE: 'Nothing active right now.', WAITING: 'Nothing is waiting on a reporter.', DONE: 'No finished incidents yet.',
  UNASSIGNED: 'Every incident has a technician.', OPEN: 'You have no open incidents.',
};

const PRIORITY_OPTIONS = [
  { value: 'P1', label: 'P1 Critical' }, { value: 'P2', label: 'P2 High' }, { value: 'P3', label: 'P3 Medium' }, { value: 'P4', label: 'P4 Low' },
];
const SLA_LABEL: Record<FilterSlaStatus, string> = {
  BREACHED: 'Breached', WARNING: 'At risk', RUNNING: 'On track', PAUSED: 'Paused', MET: 'Met', NO_POLICY: 'No SLA policy',
};

const PRIO_ORDER = { P1: 1, P2: 2, P3: 3, P4: 4 } as const;

/** P4's quick SLA filters above the list (staff only): each shows how many incidents that filter would leave. */
const ATTENTION: { key: keyof SlaAttention; label: string; active: (f: IncidentFilters) => boolean; toggle: (f: IncidentFilters, on: boolean) => IncidentFilters }[] = [
  { key: 'breached', label: 'Breached', active: (f) => f.slaStatus === 'BREACHED', toggle: (f, on) => ({ ...f, slaStatus: on ? 'BREACHED' : undefined }) },
  { key: 'atRisk', label: 'At risk', active: (f) => f.slaStatus === 'WARNING', toggle: (f, on) => ({ ...f, slaStatus: on ? 'WARNING' : undefined }) },
  { key: 'predictedMiss', label: 'Predicted to miss', active: (f) => f.predictedMiss === true, toggle: (f, on) => ({ ...f, predictedMiss: on ? true : undefined }) },
];

/** "Resolution due" cell (P4's SLA facts), refreshed with the page. */
function DeadlineCell({ row, now }: { row: IncidentRow; now: number }) {
  const d = listDeadline(row.sla, now);
  return (
    <div className="flex flex-col items-start gap-0.5">
      <span className={row.sla ? undefined : 'text-muted-foreground'}>{d.text}</span>
      {d.badge && <Badge variant={d.badge.variant}>{d.badge.label}</Badge>}
    </div>
  );
}

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
  const [sort, setSort] = useState<IncidentSort>('URGENCY');
  const opener = useRef<HTMLElement | null>(null);
  const [filters, setFilters] = useState<IncidentFilters>({});
  const debounced = useDebounced(search);
  const filterKey = JSON.stringify(filters);
  const filterCount = activeFilterCount(filters);
  const catalog = useResource(() => incidentService.loadFilterOptions(actor), [actor.email]);
  const setFilter = <K extends keyof IncidentFilters>(key: K, value: IncidentFilters[K] | undefined) => {
    setFilters((f) => { const next = { ...f }; if (value === undefined || value === '') delete next[key]; else next[key] = value; return next; });
    setPage(1);
  };
  const applyFilters = (next: IncidentFilters) => {
    setFilters(Object.fromEntries(Object.entries(next).filter(([, v]) => v !== undefined && v !== '')) as IncidentFilters);
    setPage(1);
  };

  const { data, loading, error, reload } = useResource(
    () => incidentService.list(actor, tab, debounced, page, filters, sort),
    [actor.email, tab, debounced, page, filterKey, sort]
  );
  const staff = audience === 'staff';
  const now = Date.now();
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
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant={sortPriority ? 'secondary' : 'outline'}
              size="sm"
              onClick={() => { setSortPriority((v) => !v); setSort('URGENCY'); }}
              aria-pressed={sortPriority}
              title="Sort by priority (P1 first)"
            >
              <ArrowUpDown className="h-3.5 w-3.5" aria-hidden="true" />
              Priority
            </Button>
            {staff && (
              <Button
                type="button"
                variant={sort === 'DEADLINE' ? 'secondary' : 'outline'}
                size="sm"
                onClick={() => { setSort((s) => (s === 'DEADLINE' ? 'URGENCY' : 'DEADLINE')); setSortPriority(false); setPage(1); }}
                aria-pressed={sort === 'DEADLINE'}
                title="Sort by resolution deadline (nearest first)"
              >
                <CalendarClock className="h-3.5 w-3.5" aria-hidden="true" />
                Deadline
              </Button>
            )}
            <div className="relative w-full sm:w-72">
              <Label htmlFor="incident-search" className="sr-only">Search incidents</Label>
              <Input id="incident-search" type="search" value={search} onChange={(e) => { setSearch(e.target.value); setPage(1); }} placeholder="Search by ID, title or person" />
            </div>
          </div>
        </div>

        {staff && data?.attention && (
          <div role="group" aria-label="Service level attention" className="mb-3 flex flex-wrap items-center gap-2">
            <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Service level</span>
            {ATTENTION.map((a) => {
              const on = a.active(filters);
              return (
                <Button key={a.key} type="button" size="sm" variant={on ? 'secondary' : 'outline'} aria-pressed={on}
                  onClick={() => applyFilters(a.toggle(filters, !on))}>
                  {a.label}
                  <span className="ml-1 tabular-nums text-muted-foreground">{data.attention![a.key]}</span>
                </Button>
              );
            })}
          </div>
        )}

        <div role="group" aria-label="Filter incidents" className="mb-3 flex flex-wrap items-center gap-2">
          <FilterSelect label="Priority" allLabel="All priorities" value={filters.priority ?? 'ALL'} options={PRIORITY_OPTIONS}
            onChange={(v) => setFilter('priority', v === 'ALL' ? undefined : (v as IncidentFilters['priority']))} />
          <FilterSelect label="Category" allLabel="All categories" value={filters.categoryId === undefined ? 'ALL' : String(filters.categoryId)}
            options={(catalog.data?.categories ?? []).map((c) => ({ value: String(c.id), label: c.name }))}
            onChange={(v) => setFilter('categoryId', v === 'ALL' ? undefined : Number(v))} />
          <FilterSelect label="Service" allLabel="All services" value={filters.serviceId === undefined ? 'ALL' : String(filters.serviceId)}
            options={(catalog.data?.services ?? []).map((c) => ({ value: String(c.id), label: c.name }))}
            onChange={(v) => setFilter('serviceId', v === 'ALL' ? undefined : Number(v))} />
          {actor.role === 'ADMIN' && (
            <FilterSelect label="Technician" allLabel="All technicians" value={filters.assigneeId === undefined ? 'ALL' : String(filters.assigneeId)}
              options={(catalog.data?.technicians ?? []).map((t) => ({ value: String(t.id), label: t.name }))}
              onChange={(v) => setFilter('assigneeId', v === 'ALL' ? undefined : Number(v))} />
          )}
          <FilterSelect label="SLA status" allLabel="Any SLA status" value={filters.slaStatus ?? 'ALL'}
            options={FILTER_SLA_STATUSES.map((v) => ({ value: v, label: SLA_LABEL[v] }))}
            onChange={(v) => setFilter('slaStatus', v === 'ALL' ? undefined : (v as FilterSlaStatus))} />
          <div className="flex flex-wrap items-center gap-1.5">
            <Label htmlFor="incident-from" className="text-xs text-muted-foreground">Reported from</Label>
            <Input id="incident-from" type="date" className="w-[150px]" value={filters.from ?? ''} max={filters.to || undefined}
              onChange={(e) => setFilter('from', e.target.value || undefined)} />
            <Label htmlFor="incident-to" className="text-xs text-muted-foreground">to</Label>
            <Input id="incident-to" type="date" className="w-[150px]" value={filters.to ?? ''} min={filters.from || undefined}
              onChange={(e) => setFilter('to', e.target.value || undefined)} />
          </div>
          <ClearFilters show={filterCount > 0} onClear={() => { setFilters({}); setPage(1); }} />
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
                  <EmptyState icon={Inbox} title={filterCount > 0 ? 'No incidents match your filters.' : debounced ? 'No incidents match your search.' : EMPTY[t.id]} />
                ) : (
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>Incident</TableHead>
                        <TableHead>Priority</TableHead>
                        <TableHead>Status</TableHead>
                        {staff && <TableHead>Resolution due</TableHead>}
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
                          {staff && <TableCell className="whitespace-nowrap"><DeadlineCell row={i} now={now} /></TableCell>}
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
