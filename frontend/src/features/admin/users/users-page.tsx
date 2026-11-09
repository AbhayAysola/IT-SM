'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { ArrowDown, ArrowUp, MoreHorizontal, Pencil, Plus, ShieldOff, Trash2, UserCheck, UserPlus, UsersRound } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ConfirmDialog } from '../components/confirm-dialog';
import { ClearFilters, FilterSelect, SearchBox } from '../components/filters';
import { PageHeader } from '../components/page-header';
import { Pagination } from '../components/pagination';
import { EmptyState, ErrorState, TableSkeleton } from '../components/states';
import { ROLE_LABEL, RoleBadge, STATUS_LABEL, StatusBadge, formatDate } from '../components/status-badges';
import { useToast } from '../components/toast';
import { useDebounced, useResource } from '../hooks/use-resource';
import { userService, type UserQuery } from '../services/user-service';
import { DEPARTMENTS, type AccountStatus, type AdminRole, type ManagedUser } from '../types';
import { UserFormDialog } from './user-form-dialog';

const PAGE_SIZE = 8;
type SortKey = NonNullable<UserQuery['sort']>;

const oneOf = <T extends string>(v: string | null, allowed: readonly T[]): T | 'ALL' => (v && (allowed as readonly string[]).includes(v) ? (v as T) : 'ALL');

function SortHead({ k, sort, dir, onSort, children }: { k: SortKey; sort: SortKey; dir: 'asc' | 'desc'; onSort: (k: SortKey) => void; children: React.ReactNode }) {
  return (
    <TableHead aria-sort={sort === k ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button type="button" onClick={() => onSort(k)} className="inline-flex items-center gap-1 rounded font-medium hover:text-foreground">
        {children}
        {sort === k && (dir === 'asc' ? <ArrowUp className="h-3.5 w-3.5" aria-hidden="true" /> : <ArrowDown className="h-3.5 w-3.5" aria-hidden="true" />)}
      </button>
    </TableHead>
  );
}

export default function UsersPage() {
  const toast = useToast();
  const router = useRouter();
  const params = useSearchParams();

  const [search, setSearch] = useState(params.get('q') ?? '');
  const [role, setRole] = useState<AdminRole | 'ALL'>(oneOf(params.get('role'), ['EMPLOYEE', 'TECHNICIAN', 'ADMIN'] as const));
  const [status, setStatus] = useState<AccountStatus | 'ALL'>(oneOf(params.get('status'), ['ACTIVE', 'SUSPENDED', 'INVITED'] as const));
  const [department, setDepartment] = useState('ALL');
  const [sort, setSort] = useState<SortKey>('name');
  const [dir, setDir] = useState<'asc' | 'desc'>('asc');
  const [page, setPage] = useState(1);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ManagedUser | null>(null);
  const [deleting, setDeleting] = useState<ManagedUser | null>(null);
  const [suspending, setSuspending] = useState<ManagedUser | null>(null);
  const [bulkSuspend, setBulkSuspend] = useState(false);

  const debounced = useDebounced(search);
  useEffect(() => setPage(1), [debounced, role, status, department]);

  // Keep the URL shareable for the filters the overview links to.
  useEffect(() => {
    const p = new URLSearchParams();
    if (debounced) p.set('q', debounced);
    if (role !== 'ALL') p.set('role', role);
    if (status !== 'ALL') p.set('status', status);
    const qs = p.toString();
    router.replace(qs ? `/admin/users?${qs}` : '/admin/users', { scroll: false });
  }, [debounced, role, status, router]);

  const { data, loading, error, reload } = useResource(
    () => userService.list({ search: debounced, role, status, department, sort, dir, page, pageSize: PAGE_SIZE }),
    [debounced, role, status, department, sort, dir, page]
  );

  const items = useMemo(() => data?.items ?? [], [data]);
  const filtersActive = !!(search || role !== 'ALL' || status !== 'ALL' || department !== 'ALL');
  const clear = () => { setSearch(''); setRole('ALL'); setStatus('ALL'); setDepartment('ALL'); };

  // Drop selections that are no longer visible on this page.
  useEffect(() => {
    setSelected((s) => new Set(Array.from(s).filter((id) => items.some((u) => u.id === id))));
  }, [data]); // eslint-disable-line react-hooks/exhaustive-deps

  const allChecked = items.length > 0 && items.every((u) => selected.has(u.id));
  const toggleAll = () => setSelected(allChecked ? new Set() : new Set(items.map((u) => u.id)));
  const toggleOne = (id: string) => setSelected((s) => { const n = new Set(s); n.has(id) ? n.delete(id) : n.add(id); return n; });

  const sortBy = (k: SortKey) => {
    if (sort === k) setDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else { setSort(k); setDir('asc'); }
    setPage(1);
  };

  const reactivate = async (u: ManagedUser) => {
    try {
      await userService.setStatus(u.id, 'ACTIVE');
      toast.success(`${u.name} was reactivated.`);
      reload();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not reactivate the user.');
    }
  };

  const selectedUsers = useMemo(() => items.filter((u) => selected.has(u.id)), [items, selected]);

  return (
    <>
      <PageHeader
        title="Users"
        description="Everyone who can sign in to IT-SM. Change roles, suspend access or invite new people."
        actions={
          <Button onClick={() => { setEditing(null); setFormOpen(true); }}>
            <Plus aria-hidden="true" /> Add user
          </Button>
        }
      />

      <Card>
        <div className="flex flex-col gap-3 border-b p-4 lg:flex-row lg:items-center">
          <SearchBox value={search} onChange={setSearch} label="Search users" placeholder="Search by name, email or department" />
          <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
            <FilterSelect label="Filter by role" value={role} onChange={(v) => setRole(v as AdminRole | 'ALL')} allLabel="All roles"
              options={(Object.keys(ROLE_LABEL) as AdminRole[]).map((r) => ({ value: r, label: ROLE_LABEL[r] }))} />
            <FilterSelect label="Filter by status" value={status} onChange={(v) => setStatus(v as AccountStatus | 'ALL')} allLabel="All statuses"
              options={(Object.keys(STATUS_LABEL) as AccountStatus[]).map((s) => ({ value: s, label: STATUS_LABEL[s] }))} />
            <FilterSelect label="Filter by department" value={department} onChange={setDepartment} allLabel="All departments"
              options={DEPARTMENTS.map((d) => ({ value: d, label: d }))} />
            <ClearFilters show={filtersActive} onClear={clear} />
          </div>
        </div>

        {selected.size > 0 && (
          <div className="flex flex-wrap items-center gap-3 border-b bg-muted px-4 py-2" role="region" aria-label="Bulk actions">
            <p className="text-sm font-medium" aria-live="polite">{selected.size} selected</p>
            <Button size="sm" variant="outline" onClick={() => setBulkSuspend(true)}>
              <ShieldOff aria-hidden="true" /> Suspend selected
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>Clear selection</Button>
          </div>
        )}

        {error && !data ? (
          <ErrorState message={error} onRetry={reload} />
        ) : loading && !data ? (
          <TableSkeleton label="Loading users" />
        ) : items.length === 0 && !loading ? (
          filtersActive ? (
            <EmptyState title="No users match your filters" description="Try a different search term or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
          ) : (
            <EmptyState icon={UsersRound} title="No users yet" description="Add the first person to get started." action={<Button size="sm" onClick={() => { setEditing(null); setFormOpen(true); }}><UserPlus aria-hidden="true" /> Add user</Button>} />
          )
        ) : (
          <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
            {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
            <Table>
              <TableHeader className="bg-muted/60">
                <TableRow>
                  <TableHead className="w-10">
                    <input type="checkbox" aria-label="Select all users on this page" checked={allChecked} onChange={toggleAll} className="h-4 w-4 accent-primary" />
                  </TableHead>
                  <SortHead k="name" sort={sort} dir={dir} onSort={sortBy}>User</SortHead>
                  <TableHead>Role</TableHead>
                  <TableHead>Department</TableHead>
                  <TableHead>Status</TableHead>
                  <SortHead k="lastLoginAt" sort={sort} dir={dir} onSort={sortBy}>Last sign-in</SortHead>
                  <TableHead className="w-12"><span className="sr-only">Actions</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((u) => (
                  <TableRow key={u.id} data-state={selected.has(u.id) ? 'selected' : undefined}>
                    <TableCell>
                      <input type="checkbox" aria-label={`Select ${u.name}`} checked={selected.has(u.id)} onChange={() => toggleOne(u.id)} className="h-4 w-4 accent-primary" />
                    </TableCell>
                    <TableCell>
                      <div className="font-medium text-foreground">{u.name}</div>
                      <div className="text-xs text-muted-foreground">{u.email}</div>
                    </TableCell>
                    <TableCell><RoleBadge role={u.role} /></TableCell>
                    <TableCell className="text-muted-foreground">{u.department}</TableCell>
                    <TableCell><StatusBadge status={u.status} /></TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">{formatDate(u.lastLoginAt)}</TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" aria-label={`Actions for ${u.name}`}><MoreHorizontal aria-hidden="true" /></Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem onSelect={() => { setEditing(u); setFormOpen(true); }}>
                            <Pencil aria-hidden="true" /> Edit
                          </DropdownMenuItem>
                          {u.status === 'SUSPENDED' ? (
                            <DropdownMenuItem onSelect={() => reactivate(u)}><UserCheck aria-hidden="true" /> Reactivate</DropdownMenuItem>
                          ) : (
                            <DropdownMenuItem onSelect={() => setSuspending(u)}><ShieldOff aria-hidden="true" /> Suspend</DropdownMenuItem>
                          )}
                          <DropdownMenuSeparator />
                          <DropdownMenuItem onSelect={() => setDeleting(u)} className="text-destructive focus:bg-destructive/10 focus:text-destructive">
                            <Trash2 aria-hidden="true" /> Delete
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={data?.page ?? 1} pageSize={PAGE_SIZE} total={data?.total ?? 0} onPage={setPage} noun="users" />
          </div>
        )}
      </Card>

      <UserFormDialog
        open={formOpen}
        onOpenChange={setFormOpen}
        user={editing}
        onSaved={(u, mode) => {
          toast.success(mode === 'created' ? `${u.name} was invited.` : `${u.name} was updated.`);
          reload();
        }}
      />

      <ConfirmDialog
        open={!!suspending}
        onOpenChange={(o) => !o && setSuspending(null)}
        title={`Suspend ${suspending?.name ?? ''}?`}
        description="They will be signed out and unable to sign in until you reactivate them. Their history is kept."
        confirmLabel="Suspend user"
        destructive
        onConfirm={async () => {
          await userService.setStatus(suspending!.id, 'SUSPENDED');
          toast.success(`${suspending!.name} was suspended.`);
          reload();
        }}
      />

      <ConfirmDialog
        open={!!deleting}
        onOpenChange={(o) => !o && setDeleting(null)}
        title={`Delete ${deleting?.name ?? ''}?`}
        description={<>This permanently removes <strong>{deleting?.email}</strong>{deleting?.role === 'TECHNICIAN' ? ' and their technician profile' : ''}. This cannot be undone. Consider suspending instead.</>}
        confirmLabel="Delete user"
        destructive
        onConfirm={async () => {
          await userService.remove(deleting!.id);
          toast.success(`${deleting!.name} was deleted.`);
          setSelected((s) => { const n = new Set(s); n.delete(deleting!.id); return n; });
          reload();
        }}
      />

      <ConfirmDialog
        open={bulkSuspend}
        onOpenChange={setBulkSuspend}
        title={`Suspend ${selected.size} user${selected.size === 1 ? '' : 's'}?`}
        description={<>They will lose access until reactivated: {selectedUsers.map((u) => u.name).join(', ')}.</>}
        confirmLabel="Suspend selected"
        destructive
        onConfirm={async () => {
          const { changed, skipped } = await userService.setStatusMany(Array.from(selected), 'SUSPENDED');
          if (changed) toast.success(`${changed} user${changed === 1 ? '' : 's'} suspended.`);
          if (skipped.length) toast.error(`Skipped ${skipped.join(', ')}: the only active administrator cannot be suspended.`);
          setSelected(new Set());
          reload();
        }}
      />
    </>
  );
}
