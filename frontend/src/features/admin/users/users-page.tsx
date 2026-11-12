'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { KeyRound, LogOut, MoreHorizontal, Pencil, Plus, ShieldOff, UserCheck, UserCog, UserPlus, UsersRound } from 'lucide-react';
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
import { ActiveBadge, ROLE_LABEL, RoleBadge, formatDate } from '../components/status-badges';
import { useToast } from '../components/toast';
import { useListState } from '../hooks/use-list';
import { useResource } from '../hooks/use-resource';
import { userApi, type UserSort } from '../services/user-api';
import { ADMIN_ROLES, ServiceError, type AdminRole, type AdminUser } from '../types';
import { ChangeRoleDialog, ResetPasswordDialog } from './user-action-dialogs';
import { UserFormDialog } from './user-form-dialog';

const PAGE_SIZE = 10;
const oneOf = <T extends string>(v: string | null, allowed: readonly T[]): T | 'ALL' => (v && (allowed as readonly string[]).includes(v) ? (v as T) : 'ALL');
const msg = (e: unknown, fallback: string) => (e instanceof ServiceError ? e.message : fallback);

export default function UsersPage() {
  const toast = useToast();
  const router = useRouter();
  const params = useSearchParams();

  const [role, setRole] = useState<AdminRole | 'ALL'>(oneOf(params.get('role'), ADMIN_ROLES));
  const [active, setActive] = useState<'true' | 'false' | 'ALL'>(oneOf(params.get('active'), ['true', 'false'] as const));
  const list = useListState<UserSort>('name', 'asc', [role, active], params.get('q') ?? '');

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<AdminUser | null>(null);
  const [roleFor, setRoleFor] = useState<AdminUser | null>(null);
  const [resetFor, setResetFor] = useState<AdminUser | null>(null);
  const [toggling, setToggling] = useState<AdminUser | null>(null);
  const [revoking, setRevoking] = useState<AdminUser | null>(null);

  useEffect(() => {
    const p = new URLSearchParams();
    if (list.debounced) p.set('q', list.debounced);
    if (role !== 'ALL') p.set('role', role);
    if (active !== 'ALL') p.set('active', active);
    const qs = p.toString();
    router.replace(qs ? `/admin/users?${qs}` : '/admin/users', { scroll: false });
  }, [list.debounced, role, active, router]);

  const { data, loading, error, reload } = useResource(
    () => userApi.list({ search: list.debounced, role, active, sort: list.sort, dir: list.dir, page: list.page, size: PAGE_SIZE }),
    [list.debounced, role, active, list.sort, list.dir, list.page]
  );
  const items = useMemo(() => data?.items ?? [], [data]);
  const filtersActive = !!(list.search || role !== 'ALL' || active !== 'ALL');
  const clear = () => { list.setSearch(''); setRole('ALL'); setActive('ALL'); };
  const openAdd = () => { setEditing(null); setFormOpen(true); };

  return (
    <>
      <PageHeader
        title="Users"
        description="Everyone who can sign in to IT-SM. Accounts are deactivated, never deleted, so history stays attributable."
        actions={<Button onClick={openAdd}><Plus aria-hidden="true" /> Add user</Button>}
      />
      <Card>
        <div className="flex flex-col gap-3 border-b p-4 lg:flex-row lg:items-center">
          <SearchBox value={list.search} onChange={list.setSearch} label="Search users" placeholder="Search by name or email" />
          <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
            <FilterSelect label="Filter by role" value={role} onChange={(v) => setRole(v as AdminRole | 'ALL')} allLabel="All roles"
              options={ADMIN_ROLES.map((r) => ({ value: r, label: ROLE_LABEL[r] }))} />
            <FilterSelect label="Filter by status" value={active} onChange={(v) => setActive(v as 'true' | 'false' | 'ALL')} allLabel="All statuses"
              options={[{ value: 'true', label: 'Active' }, { value: 'false', label: 'Inactive' }]} />
            <ClearFilters show={filtersActive} onClear={clear} />
          </div>
        </div>

        {error && !data ? (
          <ErrorState message={error} onRetry={reload} />
        ) : loading && !data ? (
          <TableSkeleton label="Loading users" />
        ) : items.length === 0 && !loading ? (
          filtersActive ? (
            <EmptyState title="No users match your filters" description="Try a different search term or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
          ) : (
            <EmptyState icon={UsersRound} title="No users yet" description="Add the first person to get started." action={<Button size="sm" onClick={openAdd}><UserPlus aria-hidden="true" /> Add user</Button>} />
          )
        ) : (
          <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
            {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
            <Table>
              <TableHeader className="bg-muted/60">
                <TableRow>
                  <SortHead k="name" sort={list.sort} dir={list.dir} onSort={list.sortBy}>User</SortHead>
                  <SortHead k="role" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Role</SortHead>
                  <SortHead k="active" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Status</SortHead>
                  <SortHead k="createdAt" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Created</SortHead>
                  <TableHead className="w-12"><span className="sr-only">Actions</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell>
                      <div className="font-medium text-foreground">{u.name}</div>
                      <div className="text-xs text-muted-foreground">{u.email}</div>
                    </TableCell>
                    <TableCell><RoleBadge role={u.role} /></TableCell>
                    <TableCell><ActiveBadge active={u.active} /></TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">{formatDate(u.createdAt)}</TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" aria-label={`Actions for ${u.name} (${u.email})`}><MoreHorizontal aria-hidden="true" /></Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem onSelect={() => { setEditing(u); setFormOpen(true); }}><Pencil aria-hidden="true" /> Edit details</DropdownMenuItem>
                          <DropdownMenuItem onSelect={() => setRoleFor(u)}><UserCog aria-hidden="true" /> Change role</DropdownMenuItem>
                          <DropdownMenuItem onSelect={() => setResetFor(u)}><KeyRound aria-hidden="true" /> Reset password</DropdownMenuItem>
                          <DropdownMenuItem onSelect={() => setRevoking(u)}><LogOut aria-hidden="true" /> Revoke sessions</DropdownMenuItem>
                          <DropdownMenuSeparator />
                          {u.active ? (
                            <DropdownMenuItem onSelect={() => setToggling(u)} className="text-destructive focus:bg-destructive/10 focus:text-destructive"><ShieldOff aria-hidden="true" /> Deactivate</DropdownMenuItem>
                          ) : (
                            <DropdownMenuItem onSelect={() => setToggling(u)}><UserCheck aria-hidden="true" /> Activate</DropdownMenuItem>
                          )}
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={(data?.page ?? 0) + 1} pageSize={PAGE_SIZE} total={data?.totalItems ?? 0} onPage={(p) => list.setPage(p - 1)} noun="users" />
          </div>
        )}
      </Card>

      <UserFormDialog open={formOpen} onOpenChange={setFormOpen} user={editing}
        onSaved={(u, mode) => { toast.success(mode === 'created' ? `${u.name} was added.` : `${u.name} was updated.`); reload(); }} />
      <ChangeRoleDialog user={roleFor} onClose={() => setRoleFor(null)} onSaved={(u) => { toast.success(`${u.name} is now ${ROLE_LABEL[u.role]}.`); reload(); }} />
      <ResetPasswordDialog user={resetFor} onClose={() => setResetFor(null)} onDone={(u) => { toast.success(`Password reset for ${u.name}. They were signed out.`); reload(); }} />

      <ConfirmDialog
        open={!!toggling} onOpenChange={(o) => !o && setToggling(null)}
        title={`${toggling?.active ? 'Deactivate' : 'Activate'} ${toggling?.name ?? ''}?`}
        description={toggling?.active
          ? 'They are signed out and cannot sign in until reactivated. Their history is kept. You cannot deactivate yourself or the last active administrator.'
          : 'They will be able to sign in again.'}
        confirmLabel={toggling?.active ? 'Deactivate user' : 'Activate user'} destructive={!!toggling?.active}
        onConfirm={async () => {
          try {
            const u = await userApi.setActive(toggling!.id, !toggling!.active, toggling!.version);
            toast.success(`${u.name} was ${u.active ? 'activated' : 'deactivated'}.`);
            reload();
          } catch (e) {
            if (e instanceof ServiceError && e.code === 'STALE') {
              // Pick up the latest version so confirming again acts on what is now stored.
              try { setToggling(await userApi.get(toggling!.id)); } catch { /* the message below still applies */ }
              reload();
            }
            throw new Error(msg(e, 'Could not change the account.'));
          }
        }}
      />
      <ConfirmDialog
        open={!!revoking} onOpenChange={(o) => !o && setRevoking(null)}
        title={`Revoke sessions of ${revoking?.name ?? ''}?`}
        description="Every sign-in this person holds stops working and they must sign in again. Nothing else changes."
        confirmLabel="Revoke sessions" destructive
        onConfirm={async () => {
          try { await userApi.revokeSessions(revoking!.id); toast.success(`Sessions of ${revoking!.name} were revoked.`); reload(); }
          catch (e) { throw new Error(msg(e, 'Could not revoke sessions.')); }
        }}
      />
    </>
  );
}
