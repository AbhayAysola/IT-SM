'use client';

import React, { useEffect, useState } from 'react';
import { AlertTriangle, MoreHorizontal, Pause, Pencil, Play, Plus, Timer, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ConfirmDialog } from '../components/confirm-dialog';
import { ClearFilters, FilterSelect, SearchBox } from '../components/filters';
import { PageHeader } from '../components/page-header';
import { Pagination } from '../components/pagination';
import { SortHead } from '../components/sort-head';
import { EmptyState, ErrorState, TableSkeleton } from '../components/states';
import { ActiveBadge, PriorityBadge } from '../components/status-badges';
import { useToast } from '../components/toast';
import { formatMinutes } from '../format';
import { useDebounced, useResource } from '../hooks/use-resource';
import { itServiceService } from '../services/service-service';
import { slaService, type SlaQuery } from '../services/sla-service';
import { PRIORITIES, PRIORITY_LABEL, type ActiveStatus, type Priority, type SlaPolicyView } from '../types';
import { SlaFormDialog } from './sla-form-dialog';

const PAGE_SIZE = 8;
type SortKey = NonNullable<SlaQuery['sort']>;

export default function SlaPoliciesPage() {
  const toast = useToast();
  const [search, setSearch] = useState('');
  const [serviceId, setServiceId] = useState('ALL');
  const [priority, setPriority] = useState<Priority | 'ALL'>('ALL');
  const [status, setStatus] = useState<ActiveStatus | 'ALL'>('ALL');
  const [sort, setSort] = useState<SortKey>('service');
  const [dir, setDir] = useState<'asc' | 'desc'>('asc');
  const [page, setPage] = useState(1);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<SlaPolicyView | null>(null);
  const [deleting, setDeleting] = useState<SlaPolicyView | null>(null);

  const debounced = useDebounced(search);
  useEffect(() => setPage(1), [debounced, serviceId, priority, status]);

  const { data, loading, error, reload } = useResource(
    () => slaService.list({ search: debounced, serviceId, priority, status, sort, dir, page, pageSize: PAGE_SIZE }),
    [debounced, serviceId, priority, status, sort, dir, page]
  );
  const gaps = useResource(() => slaService.coverageGaps(), [data]);
  const serviceOptions = useResource(() => itServiceService.options(), []);

  const items = data?.items ?? [];
  const filtersActive = !!(search || serviceId !== 'ALL' || priority !== 'ALL' || status !== 'ALL');
  const clear = () => { setSearch(''); setServiceId('ALL'); setPriority('ALL'); setStatus('ALL'); };
  const sortBy = (k: SortKey) => { if (sort === k) setDir((d) => (d === 'asc' ? 'desc' : 'asc')); else { setSort(k); setDir('asc'); } setPage(1); };
  const openAdd = () => { setEditing(null); setFormOpen(true); };

  const toggle = async (p: SlaPolicyView) => {
    const next: ActiveStatus = p.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    try {
      await slaService.setStatus(p.id, next);
      toast.success(`${p.priority} policy for ${p.serviceName} was ${next === 'ACTIVE' ? 'enabled' : 'disabled'}.`);
      reload();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not change the policy.');
    }
  };

  return (
    <>
      <PageHeader
        title="SLA policies"
        description="Response and resolution targets for each service and priority. Timers and escalation use the active policy."
        actions={<Button onClick={openAdd}><Plus aria-hidden="true" /> Add policy</Button>}
      />

      {gaps.data && gaps.data.length > 0 && (
        <div role="note" className="mb-4 flex items-start gap-2.5 rounded-md border border-warning/50 bg-warning/15 p-3 text-sm text-warning-foreground" data-testid="coverage-gaps">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <p>
            <strong>{gaps.data.length} active {gaps.data.length === 1 ? 'service has' : 'services have'} no SLA policy:</strong> {gaps.data.join(', ')}.
          </p>
        </div>
      )}

      <Card>
        <div className="flex flex-col gap-3 border-b p-4 lg:flex-row lg:items-center">
          <SearchBox value={search} onChange={setSearch} label="Search policies" placeholder="Search by service or priority" />
          <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
            <FilterSelect label="Filter by service" value={serviceId} onChange={setServiceId} allLabel="All services"
              options={(serviceOptions.data ?? []).map((s) => ({ value: s.id, label: s.name }))} />
            <FilterSelect label="Filter by priority" value={priority} onChange={(v) => setPriority(v as Priority | 'ALL')} allLabel="Any priority"
              options={PRIORITIES.map((p) => ({ value: p, label: PRIORITY_LABEL[p] }))} />
            <FilterSelect label="Filter by status" value={status} onChange={(v) => setStatus(v as ActiveStatus | 'ALL')} allLabel="All statuses"
              options={[{ value: 'ACTIVE', label: 'Enabled' }, { value: 'INACTIVE', label: 'Disabled' }]} />
            <ClearFilters show={filtersActive} onClear={clear} />
          </div>
        </div>

        {error && !data ? <ErrorState message={error} onRetry={reload} />
          : loading && !data ? <TableSkeleton label="Loading SLA policies" />
          : items.length === 0 && !loading ? (
            filtersActive
              ? <EmptyState title="No policies match your filters" description="Try a different search term or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
              : <EmptyState icon={Timer} title="No SLA policies yet" description="Add a policy to start measuring response and resolution." action={<Button size="sm" onClick={openAdd}><Plus aria-hidden="true" /> Add policy</Button>} />
          ) : (
            <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
              {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
              <Table>
                <TableHeader className="bg-muted/60">
                  <TableRow>
                    <SortHead k="service" sort={sort} dir={dir} onSort={sortBy}>Service</SortHead>
                    <SortHead k="priority" sort={sort} dir={dir} onSort={sortBy}>Priority</SortHead>
                    <SortHead k="responseMinutes" sort={sort} dir={dir} onSort={sortBy}>Response</SortHead>
                    <SortHead k="resolutionMinutes" sort={sort} dir={dir} onSort={sortBy}>Resolution</SortHead>
                    <TableHead>Summary</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="w-12"><span className="sr-only">Actions</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((p) => (
                    <TableRow key={p.id}>
                      <TableCell>
                        <div className="font-medium text-foreground">{p.serviceName}</div>
                        {p.serviceStatus === 'INACTIVE' && <div className="text-xs text-muted-foreground">Service inactive</div>}
                      </TableCell>
                      <TableCell><PriorityBadge priority={p.priority} /></TableCell>
                      <TableCell className="whitespace-nowrap">{formatMinutes(p.responseMinutes)}</TableCell>
                      <TableCell className="whitespace-nowrap">{formatMinutes(p.resolutionMinutes)}</TableCell>
                      <TableCell className="min-w-[220px] text-sm text-muted-foreground">
                        Respond within {formatMinutes(p.responseMinutes)}, resolve within {formatMinutes(p.resolutionMinutes)}.
                      </TableCell>
                      <TableCell><ActiveBadge status={p.status} /></TableCell>
                      <TableCell>
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={`Actions for ${p.priority} policy on ${p.serviceName}`}><MoreHorizontal aria-hidden="true" /></Button></DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            <DropdownMenuItem onSelect={() => { setEditing(p); setFormOpen(true); }}><Pencil aria-hidden="true" /> Edit targets</DropdownMenuItem>
                            <DropdownMenuItem onSelect={() => toggle(p)}>
                              {p.status === 'ACTIVE' ? <><Pause aria-hidden="true" /> Disable</> : <><Play aria-hidden="true" /> Enable</>}
                            </DropdownMenuItem>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem onSelect={() => setDeleting(p)} className="text-destructive focus:bg-destructive/10 focus:text-destructive"><Trash2 aria-hidden="true" /> Delete</DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Pagination page={data?.page ?? 1} pageSize={PAGE_SIZE} total={data?.total ?? 0} onPage={setPage} noun="policies" />
            </div>
          )}
      </Card>

      <SlaFormDialog open={formOpen} onOpenChange={setFormOpen} policy={editing}
        onSaved={(p, mode) => { toast.success(mode === 'created' ? `${p.priority} policy added for ${p.serviceName}.` : `${p.priority} policy for ${p.serviceName} was updated.`); reload(); }} />

      <ConfirmDialog
        open={!!deleting}
        onOpenChange={(o) => !o && setDeleting(null)}
        title={`Delete the ${deleting?.priority ?? ''} policy for ${deleting?.serviceName ?? ''}?`}
        description="Incidents of this priority on this service will no longer have SLA targets. This cannot be undone. Consider disabling the policy instead."
        confirmLabel="Delete policy"
        destructive
        onConfirm={async () => {
          await slaService.remove(deleting!.id);
          toast.success(`${deleting!.priority} policy for ${deleting!.serviceName} was deleted.`);
          reload();
        }}
      />
    </>
  );
}
