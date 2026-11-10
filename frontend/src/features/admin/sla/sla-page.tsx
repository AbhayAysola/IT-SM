'use client';

import React, { useMemo, useState } from 'react';
import { CircleCheck, CircleOff, MoreHorizontal, Pencil, Plus, Timer } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ConfirmDialog } from '../components/confirm-dialog';
import { ClearFilters, FilterSelect, SearchBox } from '../components/filters';
import { PageHeader } from '../components/page-header';
import { Pagination } from '../components/pagination';
import { SortHead } from '../components/sort-head';
import { EmptyState, ErrorState, TableSkeleton } from '../components/states';
import { ActiveBadge, PriorityBadge, formatMinutes } from '../components/status-badges';
import { useToast } from '../components/toast';
import { useListState } from '../hooks/use-list';
import { useResource } from '../hooks/use-resource';
import { slaApi, type SlaSort } from '../services/sla-api';
import { PRIORITIES, PRIORITY_LABEL, ServiceError, type Priority, type SlaPolicy } from '../types';
import { EffectiveSlaCard } from './effective-sla-card';
import { SlaFormDialog } from './sla-form-dialog';

const PAGE_SIZE = 10;
type Scope = 'ALL' | 'global' | 'service';

export default function SlaPage() {
  const toast = useToast();
  const [active, setActive] = useState<'true' | 'false' | 'ALL'>('ALL');
  const [priority, setPriority] = useState<Priority | 'ALL'>('ALL');
  const [scope, setScope] = useState<Scope>('ALL');
  const list = useListState<SlaSort>('priority', 'asc', [active, priority, scope]);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<SlaPolicy | null>(null);
  const [toggling, setToggling] = useState<SlaPolicy | null>(null);
  const [changes, setChanges] = useState(0);

  const { data, loading, error, reload } = useResource(
    () => slaApi.list({
      search: list.debounced, active, priority, global: scope === 'global' ? true : scope === 'service' ? false : undefined,
      sort: list.sort, dir: list.dir, page: list.page, size: PAGE_SIZE,
    }),
    [list.debounced, active, priority, scope, list.sort, list.dir, list.page]
  );
  const items = useMemo(() => data?.items ?? [], [data]);
  const filtersActive = !!(list.search || active !== 'ALL' || priority !== 'ALL' || scope !== 'ALL');
  const clear = () => { list.setSearch(''); setActive('ALL'); setPriority('ALL'); setScope('ALL'); };
  const openAdd = () => { setEditing(null); setFormOpen(true); };
  const refresh = () => { setChanges((n) => n + 1); reload(); };

  return (
    <>
      <PageHeader title="SLA policies" description="Response and resolution targets per priority, for a specific service or as the global fallback. This configures targets only; monitoring and escalation are separate. Policies are deactivated, never deleted."
        actions={<Button onClick={openAdd}><Plus aria-hidden="true" /> Add policy</Button>} />
      <div className="space-y-6">
        <Card>
          <div className="flex flex-col gap-3 border-b p-4 lg:flex-row lg:items-center">
            <SearchBox value={list.search} onChange={list.setSearch} label="Search policies" placeholder="Search by service name" />
            <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
              <FilterSelect label="Filter by priority" value={priority} onChange={(v) => setPriority(v as Priority | 'ALL')} allLabel="All priorities" options={PRIORITIES.map((p) => ({ value: p, label: PRIORITY_LABEL[p] }))} />
              <FilterSelect label="Filter by scope" value={scope} onChange={(v) => setScope(v as Scope)} allLabel="Global and service" options={[{ value: 'global', label: 'Global only' }, { value: 'service', label: 'Service-specific only' }]} />
              <FilterSelect label="Filter by status" value={active} onChange={(v) => setActive(v as 'true' | 'false' | 'ALL')} allLabel="All statuses" options={[{ value: 'true', label: 'Active' }, { value: 'false', label: 'Inactive' }]} />
              <ClearFilters show={filtersActive} onClear={clear} />
            </div>
          </div>
          {list.debounced.trim() && (
            <p role="note" data-testid="sla-search-hint" className="border-b bg-muted/40 px-4 py-2 text-sm text-muted-foreground">
              {scope === 'global'
                ? 'Search matches service names, and global policies have no service name, so no global policy can match a search. Clear the search to list them.'
                : 'Search matches service names only. Global policies have no service name, so they are not matched by this search. To find them, clear the search and use Scope → Global only.'}
            </p>
          )}
          {error && !data ? (
            <ErrorState message={error} onRetry={reload} />
          ) : loading && !data ? (
            <TableSkeleton label="Loading SLA policies" />
          ) : items.length === 0 && !loading ? (
            filtersActive ? (
              <EmptyState title="No policies match your filters" description="Try a different search or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
            ) : (
              <EmptyState icon={Timer} title="No SLA policies yet" description="Without a policy there is no target to apply. Add a global policy per priority first." action={<Button size="sm" onClick={openAdd}><Plus aria-hidden="true" /> Add policy</Button>} />
            )
          ) : (
            <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
              {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
              <Table>
                <TableHeader className="bg-muted/60">
                  <TableRow>
                    <TableHead>Applies to</TableHead>
                    <SortHead k="priority" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Priority</SortHead>
                    <SortHead k="responseMinutes" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Respond within</SortHead>
                    <SortHead k="resolutionMinutes" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Resolve within</SortHead>
                    <TableHead>Warn at</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="w-12"><span className="sr-only">Actions</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((p) => (
                    <TableRow key={p.id}>
                      <TableCell>
                        {p.service ? (
                          <div className="font-medium text-foreground">{p.service.name}{!p.service.active && <span className="ml-1 text-xs font-normal text-muted-foreground">(inactive service)</span>}</div>
                        ) : (
                          <Badge variant="secondary">Global default</Badge>
                        )}
                      </TableCell>
                      <TableCell><PriorityBadge priority={p.priority} /></TableCell>
                      <TableCell className="whitespace-nowrap">{formatMinutes(p.responseMinutes)}</TableCell>
                      <TableCell className="whitespace-nowrap">{formatMinutes(p.resolutionMinutes)}</TableCell>
                      <TableCell>{p.warningThresholdPct}%</TableCell>
                      <TableCell><ActiveBadge active={p.active} /></TableCell>
                      <TableCell>
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button variant="ghost" size="icon" aria-label={`Actions for ${p.service?.name ?? 'global'} ${p.priority} policy`}><MoreHorizontal aria-hidden="true" /></Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            <DropdownMenuItem onSelect={() => { setEditing(p); setFormOpen(true); }}><Pencil aria-hidden="true" /> Edit targets</DropdownMenuItem>
                            {p.active ? (
                              <DropdownMenuItem onSelect={() => setToggling(p)} className="text-destructive focus:bg-destructive/10 focus:text-destructive"><CircleOff aria-hidden="true" /> Deactivate</DropdownMenuItem>
                            ) : (
                              <DropdownMenuItem onSelect={() => setToggling(p)}><CircleCheck aria-hidden="true" /> Activate</DropdownMenuItem>
                            )}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Pagination page={(data?.page ?? 0) + 1} pageSize={PAGE_SIZE} total={data?.totalItems ?? 0} onPage={(n) => list.setPage(n - 1)} noun="policies" />
            </div>
          )}
        </Card>
        <EffectiveSlaCard refreshKey={changes} />
      </div>
      <SlaFormDialog open={formOpen} onOpenChange={setFormOpen} policy={editing}
        onSaved={(p, mode) => { toast.success(mode === 'created' ? `${p.priority} policy was added.` : `${p.priority} policy was updated.`); refresh(); }} />
      <ConfirmDialog
        open={!!toggling} onOpenChange={(o) => !o && setToggling(null)}
        title={`${toggling?.active ? 'Deactivate' : 'Activate'} this ${toggling?.priority ?? ''} policy?`}
        description={toggling?.active
          ? 'The policy stops applying. The service falls back to its global policy, and if none exists no target applies.'
          : 'The policy applies again. The server refuses this if another active policy already covers the same service and priority, or the service is inactive.'}
        confirmLabel={toggling?.active ? 'Deactivate policy' : 'Activate policy'} destructive={!!toggling?.active}
        onConfirm={async () => {
          try { const p = await slaApi.setActive(toggling!.id, !toggling!.active); toast.success(`${p.priority} policy was ${p.active ? 'activated' : 'deactivated'}.`); refresh(); }
          catch (e) { throw new Error(e instanceof ServiceError ? e.message : 'Could not change the policy.'); }
        }}
      />
    </>
  );
}
