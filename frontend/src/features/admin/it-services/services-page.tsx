'use client';

import React, { useEffect, useState } from 'react';
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
import { useDebounced, useResource } from '../hooks/use-resource';
import { itServiceService, type ServiceQuery } from '../services/service-service';
import { CRITICALITIES, CRITICALITY_LABEL, TEAMS, type ActiveStatus, type Criticality, type ServiceView } from '../types';
import { ServiceFormDialog } from './service-form-dialog';

const PAGE_SIZE = 8;
type SortKey = NonNullable<ServiceQuery['sort']>;

export default function ServicesPage() {
  const toast = useToast();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<ActiveStatus | 'ALL'>('ALL');
  const [criticality, setCriticality] = useState<Criticality | 'ALL'>('ALL');
  const [team, setTeam] = useState('ALL');
  const [sort, setSort] = useState<SortKey>('name');
  const [dir, setDir] = useState<'asc' | 'desc'>('asc');
  const [page, setPage] = useState(1);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ServiceView | null>(null);
  const [deactivating, setDeactivating] = useState<ServiceView | null>(null);

  const debounced = useDebounced(search);
  useEffect(() => setPage(1), [debounced, status, criticality, team]);

  const { data, loading, error, reload } = useResource(
    () => itServiceService.list({ search: debounced, status, criticality, team, sort, dir, page, pageSize: PAGE_SIZE }),
    [debounced, status, criticality, team, sort, dir, page]
  );
  const items = data?.items ?? [];
  const filtersActive = !!(search || status !== 'ALL' || criticality !== 'ALL' || team !== 'ALL');
  const clear = () => { setSearch(''); setStatus('ALL'); setCriticality('ALL'); setTeam('ALL'); };
  const sortBy = (k: SortKey) => { if (sort === k) setDir((d) => (d === 'asc' ? 'desc' : 'asc')); else { setSort(k); setDir(k === 'criticality' ? 'desc' : 'asc'); } setPage(1); };
  const openAdd = () => { setEditing(null); setFormOpen(true); };

  const activate = async (s: ServiceView) => {
    try {
      await itServiceService.setStatus(s.id, 'ACTIVE');
      toast.success(`${s.name} was activated.`);
      reload();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not activate the service.');
    }
  };

  return (
    <>
      <PageHeader
        title="IT services"
        description="The services IT supports, how critical each one is to the business, and what each depends on."
        actions={<Button onClick={openAdd}><Plus aria-hidden="true" /> Add service</Button>}
      />
      <Card>
        <div className="flex flex-col gap-3 border-b p-4 lg:flex-row lg:items-center">
          <SearchBox value={search} onChange={setSearch} label="Search services" placeholder="Search by name, description or team" />
          <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
            <FilterSelect label="Filter by criticality" value={criticality} onChange={(v) => setCriticality(v as Criticality | 'ALL')} allLabel="Any criticality"
              options={CRITICALITIES.map((c) => ({ value: c, label: CRITICALITY_LABEL[c] }))} />
            <FilterSelect label="Filter by status" value={status} onChange={(v) => setStatus(v as ActiveStatus | 'ALL')} allLabel="All statuses"
              options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'INACTIVE', label: 'Inactive' }]} />
            <FilterSelect label="Filter by owner team" value={team} onChange={setTeam} allLabel="All teams" options={TEAMS.map((t) => ({ value: t, label: t }))} />
            <ClearFilters show={filtersActive} onClear={clear} />
          </div>
        </div>

        {error && !data ? <ErrorState message={error} onRetry={reload} />
          : loading && !data ? <TableSkeleton label="Loading services" />
          : items.length === 0 && !loading ? (
            filtersActive
              ? <EmptyState title="No services match your filters" description="Try a different search term or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
              : <EmptyState icon={Server} title="No services yet" description="Add the first IT service." action={<Button size="sm" onClick={openAdd}><Plus aria-hidden="true" /> Add service</Button>} />
          ) : (
            <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
              {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
              <Table>
                <TableHeader className="bg-muted/60">
                  <TableRow>
                    <SortHead k="name" sort={sort} dir={dir} onSort={sortBy}>Service</SortHead>
                    <TableHead>Owner team</TableHead>
                    <SortHead k="criticality" sort={sort} dir={dir} onSort={sortBy}>Criticality</SortHead>
                    <TableHead>Depends on</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead className="w-12"><span className="sr-only">Actions</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((s) => (
                    <TableRow key={s.id}>
                      <TableCell className="max-w-[300px]">
                        <div className="font-medium text-foreground">{s.name}</div>
                        <div className="line-clamp-2 text-xs text-muted-foreground">{s.description}</div>
                      </TableCell>
                      <TableCell className="whitespace-nowrap text-muted-foreground">{s.ownerTeam}</TableCell>
                      <TableCell><CriticalityBadge value={s.criticality} /></TableCell>
                      <TableCell>
                        {s.dependencyNames.length === 0 ? <span className="text-sm text-muted-foreground">None</span> : (
                          <div className="flex max-w-[260px] flex-wrap gap-1">
                            {s.dependencyNames.slice(0, 2).map((n) => <Badge key={n} variant="secondary">{n}</Badge>)}
                            {s.dependencyNames.length > 2 && <Badge variant="outline" title={s.dependencyNames.slice(2).join(', ')}>+{s.dependencyNames.length - 2}</Badge>}
                          </div>
                        )}
                        {s.dependentNames.length > 0 && <div className="mt-1 text-xs text-muted-foreground">Needed by {s.dependentNames.length}</div>}
                      </TableCell>
                      <TableCell><ActiveBadge status={s.status} /></TableCell>
                      <TableCell>
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={`Actions for ${s.name}`}><MoreHorizontal aria-hidden="true" /></Button></DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            <DropdownMenuItem onSelect={() => { setEditing(s); setFormOpen(true); }}><Pencil aria-hidden="true" /> Edit</DropdownMenuItem>
                            {s.status === 'ACTIVE'
                              ? <DropdownMenuItem onSelect={() => setDeactivating(s)}><CircleOff aria-hidden="true" /> Deactivate</DropdownMenuItem>
                              : <DropdownMenuItem onSelect={() => activate(s)}><CircleCheck aria-hidden="true" /> Activate</DropdownMenuItem>}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Pagination page={data?.page ?? 1} pageSize={PAGE_SIZE} total={data?.total ?? 0} onPage={setPage} noun="services" />
            </div>
          )}
      </Card>

      <ServiceFormDialog open={formOpen} onOpenChange={setFormOpen} service={editing}
        onSaved={(s, mode) => { toast.success(mode === 'created' ? `${s.name} was added.` : `${s.name} was updated.`); reload(); }} />

      <ConfirmDialog
        open={!!deactivating}
        onOpenChange={(o) => !o && setDeactivating(null)}
        title={`Deactivate ${deactivating?.name ?? ''}?`}
        description={<>It will no longer be offered when reporting incidents{deactivating && deactivating.policyCount > 0 ? `, and its ${deactivating.policyCount} SLA ${deactivating.policyCount === 1 ? 'policy' : 'policies'} stay on record but cannot be extended` : ''}. You can activate it again later.</>}
        confirmLabel="Deactivate service"
        destructive
        onConfirm={async () => {
          await itServiceService.setStatus(deactivating!.id, 'INACTIVE');
          toast.success(`${deactivating!.name} was deactivated.`);
          reload();
        }}
      />
    </>
  );
}
