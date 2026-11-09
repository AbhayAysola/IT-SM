'use client';

import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { ArrowDown, ArrowUp, Crown, MoreHorizontal, Pencil, Plus, Trash2, UserCog } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ConfirmDialog } from '../components/confirm-dialog';
import { ClearFilters, FilterSelect, SearchBox } from '../components/filters';
import { PageHeader } from '../components/page-header';
import { Pagination } from '../components/pagination';
import { EmptyState, ErrorState, TableSkeleton } from '../components/states';
import { AVAILABILITY_LABEL, AvailabilityBadge, SKILL_LEVEL_LABEL } from '../components/status-badges';
import { useToast } from '../components/toast';
import { useDebounced, useResource } from '../hooks/use-resource';
import { technicianService, type TechnicianQuery } from '../services/technician-service';
import { SKILL_OPTIONS, TEAMS, type Availability, type TechnicianView } from '../types';
import { TechnicianFormDialog } from './technician-form-dialog';

const PAGE_SIZE = 8;
type SortKey = NonNullable<TechnicianQuery['sort']>;

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

function Workload({ current, max }: { current: number; max: number }) {
  const pct = Math.min(100, Math.round((current / max) * 100));
  const full = current >= max;
  return (
    <div className="min-w-[110px]">
      <div className="flex items-baseline justify-between text-sm">
        <span className="font-medium">{current} / {max}</span>
        {full && <span className="text-xs font-medium text-warning-foreground">Full</span>}
      </div>
      <div className="mt-1 h-1.5 overflow-hidden rounded-sm bg-muted" aria-hidden="true">
        <div className={full ? 'h-full bg-warning' : 'h-full bg-primary'} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}

export default function TechniciansPage() {
  const toast = useToast();
  const params = useSearchParams();

  const [search, setSearch] = useState('');
  const [availability, setAvailability] = useState<Availability | 'ALL'>('ALL');
  const [skill, setSkill] = useState('ALL');
  const [team, setTeam] = useState('ALL');
  const [sort, setSort] = useState<SortKey>(params.get('sort') === 'workload' ? 'workload' : 'name');
  const [dir, setDir] = useState<'asc' | 'desc'>(params.get('sort') === 'workload' ? 'desc' : 'asc');
  const [page, setPage] = useState(1);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<TechnicianView | null>(null);
  const [removing, setRemoving] = useState<TechnicianView | null>(null);

  const debounced = useDebounced(search);
  useEffect(() => setPage(1), [debounced, availability, skill, team]);

  const { data, loading, error, reload } = useResource(
    () => technicianService.list({ search: debounced, availability, skill, team, sort, dir, page, pageSize: PAGE_SIZE }),
    [debounced, availability, skill, team, sort, dir, page]
  );

  const items = data?.items ?? [];
  const filtersActive = !!(search || availability !== 'ALL' || skill !== 'ALL' || team !== 'ALL');
  const clear = () => { setSearch(''); setAvailability('ALL'); setSkill('ALL'); setTeam('ALL'); };

  const sortBy = (k: SortKey) => {
    if (sort === k) setDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else { setSort(k); setDir(k === 'workload' ? 'desc' : 'asc'); }
    setPage(1);
  };

  const quickAvailability = async (t: TechnicianView, a: Availability) => {
    try {
      await technicianService.setAvailability(t.id, a);
      toast.success(`${t.name} is now ${AVAILABILITY_LABEL[a].toLowerCase()}.`);
      reload();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not update availability.');
    }
  };

  const openAdd = () => { setEditing(null); setFormOpen(true); };

  return (
    <>
      <PageHeader
        title="Technicians"
        description="Skills, workload limits and availability. The routing engine reads these when it recommends who should take an incident."
        actions={<Button onClick={openAdd}><Plus aria-hidden="true" /> Add profile</Button>}
      />

      <Card>
        <div className="flex flex-col gap-3 border-b p-4 lg:flex-row lg:items-center">
          <SearchBox value={search} onChange={setSearch} label="Search technicians" placeholder="Search by name, team or skill" />
          <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
            <FilterSelect label="Filter by availability" value={availability} onChange={(v) => setAvailability(v as Availability | 'ALL')} allLabel="Any availability"
              options={(Object.keys(AVAILABILITY_LABEL) as Availability[]).map((a) => ({ value: a, label: AVAILABILITY_LABEL[a] }))} />
            <FilterSelect label="Filter by skill" value={skill} onChange={setSkill} allLabel="Any skill" options={SKILL_OPTIONS.map((s) => ({ value: s, label: s }))} />
            <FilterSelect label="Filter by team" value={team} onChange={setTeam} allLabel="All teams" options={TEAMS.map((t) => ({ value: t, label: t }))} />
            <ClearFilters show={filtersActive} onClear={clear} />
          </div>
        </div>

        {error && !data ? (
          <ErrorState message={error} onRetry={reload} />
        ) : loading && !data ? (
          <TableSkeleton label="Loading technicians" />
        ) : items.length === 0 && !loading ? (
          filtersActive ? (
            <EmptyState title="No technicians match your filters" description="Try a different search term or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
          ) : (
            <EmptyState icon={UserCog} title="No technician profiles" description="Add a profile so incidents can be routed to technicians." action={<Button size="sm" onClick={openAdd}><Plus aria-hidden="true" /> Add profile</Button>} />
          )
        ) : (
          <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
            {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
            <Table>
              <TableHeader className="bg-muted/60">
                <TableRow>
                  <SortHead k="name" sort={sort} dir={dir} onSort={sortBy}>Technician</SortHead>
                  <TableHead>Team</TableHead>
                  <TableHead>Skills</TableHead>
                  <SortHead k="skillLevel" sort={sort} dir={dir} onSort={sortBy}>Level</SortHead>
                  <SortHead k="workload" sort={sort} dir={dir} onSort={sortBy}>Workload</SortHead>
                  <TableHead>Availability</TableHead>
                  <TableHead className="w-12"><span className="sr-only">Actions</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {items.map((t) => (
                  <TableRow key={t.id}>
                    <TableCell>
                      <div className="flex items-center gap-1.5 font-medium text-foreground">
                        {t.name}
                        {t.isTeamLead && <Crown className="h-3.5 w-3.5 text-warning-foreground" aria-label="Team lead" />}
                      </div>
                      <div className="text-xs text-muted-foreground">{t.email}</div>
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-muted-foreground">{t.team}</TableCell>
                    <TableCell>
                      {t.skills.length === 0 ? (
                        <span className="text-sm text-muted-foreground">None set</span>
                      ) : (
                        <div className="flex max-w-[280px] flex-wrap gap-1">
                          {t.skills.slice(0, 3).map((s) => <Badge key={s} variant="secondary">{s}</Badge>)}
                          {t.skills.length > 3 && <Badge variant="outline" title={t.skills.slice(3).join(', ')}>+{t.skills.length - 3}</Badge>}
                        </div>
                      )}
                    </TableCell>
                    <TableCell className="text-muted-foreground">{SKILL_LEVEL_LABEL[t.skillLevel]}</TableCell>
                    <TableCell><Workload current={t.currentWorkload} max={t.maxWorkload} /></TableCell>
                    <TableCell><AvailabilityBadge value={t.availability} /></TableCell>
                    <TableCell>
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" aria-label={`Actions for ${t.name}`}><MoreHorizontal aria-hidden="true" /></Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                          <DropdownMenuItem onSelect={() => { setEditing(t); setFormOpen(true); }}><Pencil aria-hidden="true" /> Edit profile</DropdownMenuItem>
                          <DropdownMenuSeparator />
                          <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">Set availability</DropdownMenuLabel>
                          {(Object.keys(AVAILABILITY_LABEL) as Availability[]).filter((a) => a !== t.availability).map((a) => (
                            <DropdownMenuItem key={a} onSelect={() => quickAvailability(t, a)}>{AVAILABILITY_LABEL[a]}</DropdownMenuItem>
                          ))}
                          <DropdownMenuSeparator />
                          <DropdownMenuItem onSelect={() => setRemoving(t)} className="text-destructive focus:bg-destructive/10 focus:text-destructive">
                            <Trash2 aria-hidden="true" /> Remove profile
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <Pagination page={data?.page ?? 1} pageSize={PAGE_SIZE} total={data?.total ?? 0} onPage={setPage} noun="technicians" />
          </div>
        )}
      </Card>

      <TechnicianFormDialog
        open={formOpen}
        onOpenChange={setFormOpen}
        technician={editing}
        onSaved={(t, mode) => {
          toast.success(mode === 'created' ? `Profile added for ${t.name}.` : `${t.name}’s profile was updated.`);
          reload();
        }}
      />

      <ConfirmDialog
        open={!!removing}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={`Remove ${removing?.name ?? ''}’s profile?`}
        description="They will no longer be offered incidents. Their user account stays, and you can add a profile again later."
        confirmLabel="Remove profile"
        destructive
        onConfirm={async () => {
          await technicianService.remove(removing!.id);
          toast.success(`${removing!.name}’s profile was removed.`);
          reload();
        }}
      />
    </>
  );
}
