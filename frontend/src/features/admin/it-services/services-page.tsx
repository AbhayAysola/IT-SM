'use client';

import React, { useMemo, useState } from 'react';
import { CircleCheck, CircleOff, MoreHorizontal, Pencil, Plus, Server } from 'lucide-react';
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
import { ActiveBadge, CriticalityBadge } from '../components/status-badges';
import { useToast } from '../components/toast';
import { useListState } from '../hooks/use-list';
import { useResource } from '../hooks/use-resource';
import { itServiceApi, type ServiceSort } from '../services/it-service-api';
import { CRITICALITY_LABEL, CRITICALITY_LEVELS, ServiceError, type ItService, type ServiceRef } from '../types';
import { ServiceFormDialog } from './service-form-dialog';

const PAGE_SIZE = 10;

function Refs({ refs, empty }: { refs: ServiceRef[]; empty: string }) {
  if (refs.length === 0) return <span className="text-xs text-muted-foreground">{empty}</span>;
  return (
    <ul className="flex max-w-[16rem] flex-wrap gap-1">
      {refs.map((r) => <li key={r.id}><Badge variant={r.active ? 'outline' : 'secondary'} title={r.active ? undefined : 'Inactive service'}>{r.name}{r.active ? '' : ' (inactive)'}</Badge></li>)}
    </ul>
  );
}

export default function ServicesPage() {
  const toast = useToast();
  const [active, setActive] = useState<'true' | 'false' | 'ALL'>('ALL');
  const [criticality, setCriticality] = useState('ALL');
  const [ownerTeam, setOwnerTeam] = useState('');
  const list = useListState<ServiceSort>('name', 'asc', [active, criticality, ownerTeam]);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ItService | null>(null);
  const [toggling, setToggling] = useState<ItService | null>(null);

  const { data, loading, error, reload } = useResource(
    () => itServiceApi.list({
      search: list.debounced, active, criticality: criticality === 'ALL' ? 'ALL' : Number(criticality), ownerTeam,
      sort: list.sort, dir: list.dir, page: list.page, size: PAGE_SIZE,
    }),
    [list.debounced, active, criticality, ownerTeam, list.sort, list.dir, list.page]
  );
  const items = useMemo(() => data?.items ?? [], [data]);
  const filtersActive = !!(list.search || active !== 'ALL' || criticality !== 'ALL' || ownerTeam);
  const clear = () => { list.setSearch(''); setActive('ALL'); setCriticality('ALL'); setOwnerTeam(''); };
  const openAdd = () => { setEditing(null); setFormOpen(true); };

  return (
    <>
      <PageHeader title="IT services" description="The services incidents are raised against, with how critical they are and what they depend on. Services are deactivated, never deleted."
        actions={<Button onClick={openAdd}><Plus aria-hidden="true" /> Add service</Button>} />
      <Card>
        <div className="flex flex-col gap-3 border-b p-4 lg:flex-row lg:items-center">
          <SearchBox value={list.search} onChange={list.setSearch} label="Search services" placeholder="Search by name or owner team" />
          <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
            <FilterSelect label="Filter by criticality" value={criticality} onChange={setCriticality} allLabel="Any criticality" options={CRITICALITY_LEVELS.map((n) => ({ value: String(n), label: CRITICALITY_LABEL[n] }))} />
            <FilterSelect label="Filter by status" value={active} onChange={(v) => setActive(v as 'true' | 'false' | 'ALL')} allLabel="All statuses" options={[{ value: 'true', label: 'Active' }, { value: 'false', label: 'Inactive' }]} />
            <ClearFilters show={filtersActive} onClear={clear} />
          </div>
        </div>
        {error && !data ? (
          <ErrorState message={error} onRetry={reload} />
        ) : loading && !data ? (
          <TableSkeleton label="Loading services" />
        ) : items.length === 0 && !loading ? (
          filtersActive ? (
            <EmptyState title="No services match your filters" description="Try a different search or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
          ) : (
            <EmptyState icon={Server} title="No IT services yet" description="Add the first service." action={<Button size="sm" onClick={openAdd}><Plus aria-hidden="true" /> Add service</Button>} />
          )
        ) : (
          <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
            {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
            <Table>
              <TableHeader className="bg-muted/60">
                <TableRow>
                  <SortHead k="name" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Service</SortHead>
                  <SortHead k="businessCriticality" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Criticality</SortHead>
                  <TableHead>Depends on</TableHead>
                  <TableHead>Depended on by</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="w-12"><span className="sr-only">Actions</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((s) => (
                  <TableRow key={s.id}>
                    <TableCell>
                      <div className="font-medium text-foreground">{s.name}</div>
                      <div className="text-xs text-muted-foreground">{s.ownerTeam ?? 'No owner team'}</div>
                    </TableCell>
                    <TableCell><CriticalityBadge value={s.businessCriticality} /></TableCell>
                    <TableCell><Refs refs={s.dependsOn} empty="None" /></TableCell>
                    <TableCell><Refs refs={s.dependents} empty="None" /></TableCell>
                    <TableCell><ActiveBadge active={s.active} /></TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" aria-label={`Actions for ${s.name}`}><MoreHorizontal aria-hidden="true" /></Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem onSelect={() => { setEditing(s); setFormOpen(true); }}><Pencil aria-hidden="true" /> Edit</DropdownMenuItem>
                          {s.active ? (
                            <DropdownMenuItem onSelect={() => setToggling(s)} className="text-destructive focus:bg-destructive/10 focus:text-destructive"><CircleOff aria-hidden="true" /> Deactivate</DropdownMenuItem>
                          ) : (
                            <DropdownMenuItem onSelect={() => setToggling(s)}><CircleCheck aria-hidden="true" /> Activate</DropdownMenuItem>
                          )}
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={(data?.page ?? 0) + 1} pageSize={PAGE_SIZE} total={data?.totalItems ?? 0} onPage={(p) => list.setPage(p - 1)} noun="services" />
          </div>
        )}
      </Card>
      <ServiceFormDialog open={formOpen} onOpenChange={setFormOpen} service={editing}
        onSaved={(s, mode) => { toast.success(mode === 'created' ? `Service “${s.name}” was added.` : `Service “${s.name}” was updated.`); reload(); }} />
      <ConfirmDialog
        open={!!toggling} onOpenChange={(o) => !o && setToggling(null)}
        title={`${toggling?.active ? 'Deactivate' : 'Activate'} “${toggling?.name ?? ''}”?`}
        description={toggling?.active
          ? 'New incidents can no longer be raised against it. The server refuses this while an active service still depends on it.'
          : 'It becomes available again. The server refuses this while a service it depends on is inactive.'}
        confirmLabel={toggling?.active ? 'Deactivate service' : 'Activate service'} destructive={!!toggling?.active}
        onConfirm={async () => {
          try { const s = await itServiceApi.setActive(toggling!.id, !toggling!.active); toast.success(`Service “${s.name}” was ${s.active ? 'activated' : 'deactivated'}.`); reload(); }
          catch (e) { throw new Error(e instanceof ServiceError ? e.message : 'Could not change the service.'); }
        }}
      />
    </>
  );
}
