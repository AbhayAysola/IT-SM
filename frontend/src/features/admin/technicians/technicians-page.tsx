'use client';

import React, { useMemo, useState } from 'react';
import { Crown, MoreHorizontal, Pencil, Plus, Power, PowerOff, Sparkles, UserCog } from 'lucide-react';
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
import { ActiveBadge } from '../components/status-badges';
import { useToast } from '../components/toast';
import { useListState } from '../hooks/use-list';
import { useResource } from '../hooks/use-resource';
import { categoryApi } from '../services/category-api';
import { technicianApi, type TechnicianSort } from '../services/technician-api';
import { ServiceError, type Technician } from '../types';
import { SkillsDialog, TechnicianFormDialog } from './technician-form-dialog';

const PAGE_SIZE = 10;
type Flag = 'true' | 'false' | 'ALL';
const YES_NO = [{ value: 'true', label: 'Yes' }, { value: 'false', label: 'No' }];

export default function TechniciansPage() {
  const toast = useToast();
  const [active, setActive] = useState<Flag>('ALL');
  const [available, setAvailable] = useState<Flag>('ALL');
  const [teamLead, setTeamLead] = useState<Flag>('ALL');
  const [category, setCategory] = useState('ALL');
  const list = useListState<TechnicianSort>('name', 'asc', [active, available, teamLead, category]);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Technician | null>(null);
  const [skillsFor, setSkillsFor] = useState<Technician | null>(null);
  const [toggling, setToggling] = useState<Technician | null>(null);

  const categories = useResource(() => categoryApi.list({ sort: 'name', size: 100 }), []);
  const { data, loading, error, reload } = useResource(
    () => technicianApi.list({
      search: list.debounced, active, available, teamLead, categoryId: category === 'ALL' ? undefined : Number(category),
      sort: list.sort, dir: list.dir, page: list.page, size: PAGE_SIZE,
    }),
    [list.debounced, active, available, teamLead, category, list.sort, list.dir, list.page]
  );
  const items = useMemo(() => data?.items ?? [], [data]);
  const filtersActive = !!(list.search || active !== 'ALL' || available !== 'ALL' || teamLead !== 'ALL' || category !== 'ALL');
  const clear = () => { list.setSearch(''); setActive('ALL'); setAvailable('ALL'); setTeamLead('ALL'); setCategory('ALL'); };
  const openAdd = () => { setEditing(null); setFormOpen(true); };

  return (
    <>
      <PageHeader
        title="Technicians"
        description="Profiles of the people who resolve incidents. “Available” is an administrator’s eligibility flag, and “Max workload” is a configured ceiling: neither says how busy someone is or whether they can be assigned right now."
        actions={<Button onClick={openAdd}><Plus aria-hidden="true" /> Add profile</Button>}
      />
      <Card>
        <div className="flex flex-col gap-3 border-b p-4 lg:flex-row lg:items-center">
          <SearchBox value={list.search} onChange={list.setSearch} label="Search technicians" placeholder="Search by name or email" />
          <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
            <FilterSelect label="Filter by profile status" value={active} onChange={(v) => setActive(v as Flag)} allLabel="Any profile status" options={[{ value: 'true', label: 'Active' }, { value: 'false', label: 'Inactive' }]} />
            <FilterSelect label="Filter by availability" value={available} onChange={(v) => setAvailable(v as Flag)} allLabel="Any availability" options={[{ value: 'true', label: 'Available' }, { value: 'false', label: 'Not available' }]} />
            <FilterSelect label="Filter by team lead" value={teamLead} onChange={(v) => setTeamLead(v as Flag)} allLabel="Lead or not" options={[{ value: 'true', label: 'Team leads' }, { value: 'false', label: 'Not team leads' }]} />
            <FilterSelect label="Filter by skill" value={category} onChange={setCategory} allLabel="Any skill" options={(categories.data?.items ?? []).map((c) => ({ value: String(c.id), label: c.name }))} />
            <ClearFilters show={filtersActive} onClear={clear} />
          </div>
        </div>

        {error && !data ? (
          <ErrorState message={error} onRetry={reload} />
        ) : loading && !data ? (
          <TableSkeleton label="Loading technicians" />
        ) : items.length === 0 && !loading ? (
          filtersActive ? (
            <EmptyState title="No technicians match your filters" description="Try a different search or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
          ) : (
            <EmptyState icon={UserCog} title="No technician profiles yet" description="Create a profile for a user whose role is Technician." action={<Button size="sm" onClick={openAdd}><Plus aria-hidden="true" /> Add profile</Button>} />
          )
        ) : (
          <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
            {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
            <Table>
              <TableHeader className="bg-muted/60">
                <TableRow>
                  <SortHead k="name" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Technician</SortHead>
                  <TableHead>Profile</TableHead>
                  <TableHead>Eligibility</TableHead>
                  <SortHead k="maxWorkload" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Max workload</SortHead>
                  <SortHead k="experienceYears" sort={list.sort} dir={list.dir} onSort={list.sortBy}>Experience</SortHead>
                  <TableHead>Skills</TableHead>
                  <TableHead className="w-12"><span className="sr-only">Actions</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((t) => (
                  <TableRow key={t.userId}>
                    <TableCell>
                      <div className="flex items-center gap-1.5 font-medium text-foreground">
                        {t.name}
                        {t.isTeamLead && <Crown className="h-3.5 w-3.5 text-warning" aria-label="Team lead" />}
                      </div>
                      <div className="text-xs text-muted-foreground">{t.email}</div>
                    </TableCell>
                    <TableCell>
                      <ActiveBadge active={t.active} />
                      {(t.userRole !== 'TECHNICIAN' || !t.userActive) && (
                        <p className="mt-1 text-xs text-warning" data-testid="account-warning">
                          {t.userRole !== 'TECHNICIAN' ? `User role is now ${t.userRole.toLowerCase()}` : 'User account is inactive'}
                        </p>
                      )}
                    </TableCell>
                    <TableCell><Badge variant={t.available ? 'success' : 'secondary'}>{t.available ? 'Available' : 'Not available'}</Badge></TableCell>
                    <TableCell className="text-muted-foreground">{t.maxWorkload}</TableCell>
                    <TableCell className="text-muted-foreground">{t.experienceYears} {t.experienceYears === 1 ? 'year' : 'years'}</TableCell>
                    <TableCell>
                      {t.skills.length === 0 ? <span className="text-xs text-muted-foreground">None</span> : (
                        <ul className="flex flex-wrap gap-1">
                          {t.skills.map((s) => (
                            <li key={s.categoryId}><Badge variant="outline" title={s.categoryActive ? undefined : 'Category is inactive'}>{s.categoryName} · {s.proficiency}</Badge></li>
                          ))}
                        </ul>
                      )}
                    </TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" aria-label={`Actions for ${t.name} (${t.email})`}><MoreHorizontal aria-hidden="true" /></Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem onSelect={() => { setEditing(t); setFormOpen(true); }}><Pencil aria-hidden="true" /> Edit details</DropdownMenuItem>
                          <DropdownMenuItem onSelect={() => setSkillsFor(t)}><Sparkles aria-hidden="true" /> Edit skills</DropdownMenuItem>
                          {t.active ? (
                            <DropdownMenuItem onSelect={() => setToggling(t)} className="text-destructive focus:bg-destructive/10 focus:text-destructive"><PowerOff aria-hidden="true" /> Deactivate profile</DropdownMenuItem>
                          ) : (
                            <DropdownMenuItem onSelect={() => setToggling(t)}><Power aria-hidden="true" /> Activate profile</DropdownMenuItem>
                          )}
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={(data?.page ?? 0) + 1} pageSize={PAGE_SIZE} total={data?.totalItems ?? 0} onPage={(p) => list.setPage(p - 1)} noun="technicians" />
          </div>
        )}
      </Card>

      <TechnicianFormDialog open={formOpen} onOpenChange={setFormOpen} technician={editing}
        onSaved={(t, mode) => { toast.success(mode === 'created' ? `Profile created for ${t.name}.` : `${t.name} was updated.`); reload(); }} />
      <SkillsDialog technician={skillsFor} onClose={() => setSkillsFor(null)} onSaved={(t) => { toast.success(`Skills of ${t.name} were saved.`); reload(); }} />

      <ConfirmDialog
        open={!!toggling} onOpenChange={(o) => !o && setToggling(null)}
        title={`${toggling?.active ? 'Deactivate' : 'Activate'} the profile of ${toggling?.name ?? ''}?`}
        description={toggling?.active
          ? 'The profile is kept for history but the technician is no longer treated as active. The server refuses this while active technicians still report to them.'
          : 'The profile becomes active again. The server refuses this if the user is no longer a technician or their manager’s profile is inactive.'}
        confirmLabel={toggling?.active ? 'Deactivate profile' : 'Activate profile'} destructive={!!toggling?.active}
        onConfirm={async () => {
          try {
            const t = await technicianApi.setActive(toggling!.userId, !toggling!.active, toggling!.version);
            toast.success(`Profile of ${t.name} was ${t.active ? 'activated' : 'deactivated'}.`);
            reload();
          } catch (e) {
            if (e instanceof ServiceError && e.code === 'STALE') {
              try { setToggling(await technicianApi.get(toggling!.userId)); } catch { /* message below still applies */ }
              reload();
            }
            throw new Error(e instanceof ServiceError ? e.message : 'Could not change the profile.');
          }
        }}
      />
    </>
  );
}
