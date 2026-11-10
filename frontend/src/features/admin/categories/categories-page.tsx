'use client';

import React, { useMemo, useState } from 'react';
import { CircleCheck, CircleOff, MoreHorizontal, Pencil, Plus, Tags } from 'lucide-react';
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
import { ActiveBadge, formatDate } from '../components/status-badges';
import { useToast } from '../components/toast';
import { useListState } from '../hooks/use-list';
import { useResource } from '../hooks/use-resource';
import { categoryApi, type CategorySort } from '../services/category-api';
import { ServiceError, type Category } from '../types';
import { CategoryFormDialog } from './category-form-dialog';

const PAGE_SIZE = 10;
const MAX_CHIPS = 4;

export default function CategoriesPage() {
  const toast = useToast();
  const [active, setActive] = useState<'true' | 'false' | 'ALL'>('ALL');
  const list = useListState<CategorySort>('name', 'asc', [active]);
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Category | null>(null);
  const [toggling, setToggling] = useState<Category | null>(null);

  const { data, loading, error, reload } = useResource(
    () => categoryApi.list({ search: list.debounced, active, sort: list.sort, dir: list.dir, page: list.page, size: PAGE_SIZE }),
    [list.debounced, active, list.sort, list.dir, list.page]
  );
  const items = useMemo(() => data?.items ?? [], [data]);
  const filtersActive = !!(list.search || active !== 'ALL');
  const clear = () => { list.setSearch(''); setActive('ALL'); };
  const openAdd = () => { setEditing(null); setFormOpen(true); };

  return (
    <>
      <PageHeader title="Incident categories" description="The categories incidents are sorted into. They are deactivated, never deleted, so existing incidents keep a valid category."
        actions={<Button onClick={openAdd}><Plus aria-hidden="true" /> Add category</Button>} />
      <Card>
        <div className="flex flex-col gap-3 border-b p-4 lg:flex-row lg:items-center">
          <SearchBox value={list.search} onChange={list.setSearch} label="Search categories" placeholder="Search by code, name or keyword" />
          <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
            <FilterSelect label="Filter by status" value={active} onChange={(v) => setActive(v as 'true' | 'false' | 'ALL')} allLabel="All statuses" options={[{ value: 'true', label: 'Active' }, { value: 'false', label: 'Inactive' }]} />
            <ClearFilters show={filtersActive} onClear={clear} />
          </div>
        </div>
        {error && !data ? (
          <ErrorState message={error} onRetry={reload} />
        ) : loading && !data ? (
          <TableSkeleton label="Loading categories" />
        ) : items.length === 0 && !loading ? (
          filtersActive ? (
            <EmptyState title="No categories match your filters" description="Try a different search or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
          ) : (
            <EmptyState icon={Tags} title="No categories yet" description="Add the first category." action={<Button size="sm" onClick={openAdd}><Plus aria-hidden="true" /> Add category</Button>} />
          )
        ) : (
          <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
            {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
            <Table>
              <TableHeader className="bg-muted/60">
                <TableRow>
                  <SortHead k="name" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Category</SortHead>
                  <SortHead k="code" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Code</SortHead>
                  <TableHead>Keywords</TableHead>
                  <TableHead>Status</TableHead>
                  <SortHead k="updatedAt" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Updated</SortHead>
                  <TableHead className="w-12"><span className="sr-only">Actions</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((c) => (
                  <TableRow key={c.id}>
                    <TableCell>
                      <div className="font-medium text-foreground">{c.name}</div>
                      {c.description && <div className="max-w-xs truncate text-xs text-muted-foreground">{c.description}</div>}
                    </TableCell>
                    <TableCell><code className="text-xs">{c.code}</code></TableCell>
                    <TableCell>
                      <ul className="flex max-w-xs flex-wrap gap-1">
                        {c.keywords.slice(0, MAX_CHIPS).map((k) => <li key={k}><Badge variant="outline">{k}</Badge></li>)}
                        {c.keywords.length > MAX_CHIPS && <li><Badge variant="secondary">+{c.keywords.length - MAX_CHIPS}</Badge></li>}
                      </ul>
                    </TableCell>
                    <TableCell><ActiveBadge active={c.active} /></TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">{formatDate(c.updatedAt)}</TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" aria-label={`Actions for ${c.name}`}><MoreHorizontal aria-hidden="true" /></Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem onSelect={() => { setEditing(c); setFormOpen(true); }}><Pencil aria-hidden="true" /> Edit</DropdownMenuItem>
                          {c.active ? (
                            <DropdownMenuItem onSelect={() => setToggling(c)} className="text-destructive focus:bg-destructive/10 focus:text-destructive"><CircleOff aria-hidden="true" /> Deactivate</DropdownMenuItem>
                          ) : (
                            <DropdownMenuItem onSelect={() => setToggling(c)}><CircleCheck aria-hidden="true" /> Activate</DropdownMenuItem>
                          )}
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={(data?.page ?? 0) + 1} pageSize={PAGE_SIZE} total={data?.totalItems ?? 0} onPage={(p) => list.setPage(p - 1)} noun="categories" />
          </div>
        )}
      </Card>
      <CategoryFormDialog open={formOpen} onOpenChange={setFormOpen} category={editing}
        onSaved={(c, mode) => { toast.success(mode === 'created' ? `Category “${c.name}” was added.` : `Category “${c.name}” was updated.`); reload(); }} />
      <ConfirmDialog
        open={!!toggling} onOpenChange={(o) => !o && setToggling(null)}
        title={`${toggling?.active ? 'Deactivate' : 'Activate'} “${toggling?.name ?? ''}”?`}
        description={toggling?.active ? 'New incidents can no longer be filed under it. Existing incidents keep it. The server refuses to deactivate the last active category.' : 'It becomes available for new incidents again.'}
        confirmLabel={toggling?.active ? 'Deactivate category' : 'Activate category'} destructive={!!toggling?.active}
        onConfirm={async () => {
          try { const c = await categoryApi.setActive(toggling!.id, !toggling!.active); toast.success(`Category “${c.name}” was ${c.active ? 'activated' : 'deactivated'}.`); reload(); }
          catch (e) { throw new Error(e instanceof ServiceError ? e.message : 'Could not change the category.'); }
        }}
      />
    </>
  );
}
