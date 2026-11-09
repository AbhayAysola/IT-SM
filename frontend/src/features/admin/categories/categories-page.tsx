'use client';

import React, { useEffect, useState } from 'react';
import { CircleOff, CircleCheck, MoreHorizontal, Pencil, Plus, Tags } from 'lucide-react';
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
import { ActiveBadge, PriorityBadge, formatDate } from '../components/status-badges';
import { useToast } from '../components/toast';
import { useDebounced, useResource } from '../hooks/use-resource';
import { categoryService, type CategoryQuery } from '../services/category-service';
import { PRIORITIES, PRIORITY_LABEL, type ActiveStatus, type IncidentCategory, type Priority } from '../types';
import { CategoryFormDialog } from './category-form-dialog';

const PAGE_SIZE = 8;
type SortKey = NonNullable<CategoryQuery['sort']>;

export default function CategoriesPage() {
  const toast = useToast();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState<ActiveStatus | 'ALL'>('ALL');
  const [priority, setPriority] = useState<Priority | 'ALL'>('ALL');
  const [sort, setSort] = useState<SortKey>('name');
  const [dir, setDir] = useState<'asc' | 'desc'>('asc');
  const [page, setPage] = useState(1);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<IncidentCategory | null>(null);
  const [deactivating, setDeactivating] = useState<IncidentCategory | null>(null);

  const debounced = useDebounced(search);
  useEffect(() => setPage(1), [debounced, status, priority]);

  const { data, loading, error, reload } = useResource(
    () => categoryService.list({ search: debounced, status, priority, sort, dir, page, pageSize: PAGE_SIZE }),
    [debounced, status, priority, sort, dir, page]
  );
  const items = data?.items ?? [];
  const filtersActive = !!(search || status !== 'ALL' || priority !== 'ALL');
  const clear = () => { setSearch(''); setStatus('ALL'); setPriority('ALL'); };
  const sortBy = (k: SortKey) => { if (sort === k) setDir((d) => (d === 'asc' ? 'desc' : 'asc')); else { setSort(k); setDir('asc'); } setPage(1); };
  const openAdd = () => { setEditing(null); setFormOpen(true); };

  const activate = async (c: IncidentCategory) => {
    try {
      await categoryService.setStatus(c.id, 'ACTIVE');
      toast.success(`${c.name} was activated.`);
      reload();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not activate the category.');
    }
  };

  return (
    <>
      <PageHeader
        title="Incident categories"
        description="How incidents are classified. Keywords drive the automatic category suggestion when someone reports a problem."
        actions={<Button onClick={openAdd}><Plus aria-hidden="true" /> Add category</Button>}
      />
      <Card>
        <div className="flex flex-col gap-3 border-b p-4 lg:flex-row lg:items-center">
          <SearchBox value={search} onChange={setSearch} label="Search categories" placeholder="Search by name, description or keyword" />
          <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
            <FilterSelect label="Filter by status" value={status} onChange={(v) => setStatus(v as ActiveStatus | 'ALL')} allLabel="All statuses"
              options={[{ value: 'ACTIVE', label: 'Active' }, { value: 'INACTIVE', label: 'Inactive' }]} />
            <FilterSelect label="Filter by default priority" value={priority} onChange={(v) => setPriority(v as Priority | 'ALL')} allLabel="Any priority"
              options={PRIORITIES.map((p) => ({ value: p, label: PRIORITY_LABEL[p] }))} />
            <ClearFilters show={filtersActive} onClear={clear} />
          </div>
        </div>

        {error && !data ? <ErrorState message={error} onRetry={reload} />
          : loading && !data ? <TableSkeleton label="Loading categories" />
          : items.length === 0 && !loading ? (
            filtersActive
              ? <EmptyState title="No categories match your filters" description="Try a different search term or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
              : <EmptyState icon={Tags} title="No categories yet" description="Add a category so incidents can be classified." action={<Button size="sm" onClick={openAdd}><Plus aria-hidden="true" /> Add category</Button>} />
          ) : (
            <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
              {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
              <Table>
                <TableHeader className="bg-muted/60">
                  <TableRow>
                    <SortHead k="name" sort={sort} dir={dir} onSort={sortBy}>Category</SortHead>
                    <TableHead>Keywords</TableHead>
                    <TableHead>Default priority</TableHead>
                    <TableHead>Status</TableHead>
                    <SortHead k="updatedAt" sort={sort} dir={dir} onSort={sortBy}>Updated</SortHead>
                    <TableHead className="w-12"><span className="sr-only">Actions</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((c) => (
                    <TableRow key={c.id}>
                      <TableCell className="max-w-[320px]">
                        <div className="font-medium text-foreground">{c.name}</div>
                        <div className="line-clamp-2 text-xs text-muted-foreground">{c.description}</div>
                      </TableCell>
                      <TableCell>
                        <div className="flex max-w-[260px] flex-wrap gap-1">
                          {c.keywords.slice(0, 3).map((k) => <Badge key={k} variant="secondary">{k}</Badge>)}
                          {c.keywords.length > 3 && <Badge variant="outline" title={c.keywords.slice(3).join(', ')}>+{c.keywords.length - 3}</Badge>}
                        </div>
                      </TableCell>
                      <TableCell><PriorityBadge priority={c.defaultPriority} /></TableCell>
                      <TableCell><ActiveBadge status={c.status} /></TableCell>
                      <TableCell className="whitespace-nowrap text-muted-foreground">{formatDate(c.updatedAt)}</TableCell>
                      <TableCell>
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild><Button variant="ghost" size="icon" aria-label={`Actions for ${c.name}`}><MoreHorizontal aria-hidden="true" /></Button></DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            <DropdownMenuItem onSelect={() => { setEditing(c); setFormOpen(true); }}><Pencil aria-hidden="true" /> Edit</DropdownMenuItem>
                            {c.status === 'ACTIVE'
                              ? <DropdownMenuItem onSelect={() => setDeactivating(c)}><CircleOff aria-hidden="true" /> Deactivate</DropdownMenuItem>
                              : <DropdownMenuItem onSelect={() => activate(c)}><CircleCheck aria-hidden="true" /> Activate</DropdownMenuItem>}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Pagination page={data?.page ?? 1} pageSize={PAGE_SIZE} total={data?.total ?? 0} onPage={setPage} noun="categories" />
            </div>
          )}
      </Card>

      <CategoryFormDialog open={formOpen} onOpenChange={setFormOpen} category={editing}
        onSaved={(c, mode) => { toast.success(mode === 'created' ? `${c.name} was added.` : `${c.name} was updated.`); reload(); }} />

      <ConfirmDialog
        open={!!deactivating}
        onOpenChange={(o) => !o && setDeactivating(null)}
        title={`Deactivate ${deactivating?.name ?? ''}?`}
        description="New incidents will no longer be classified into this category. Existing incidents keep it, and you can activate it again later."
        confirmLabel="Deactivate category"
        destructive
        onConfirm={async () => {
          await categoryService.setStatus(deactivating!.id, 'INACTIVE');
          toast.success(`${deactivating!.name} was deactivated.`);
          reload();
        }}
      />
    </>
  );
}
