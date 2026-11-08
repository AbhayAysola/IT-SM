'use client';

import React, { useEffect, useState } from 'react';
import { Eye, ScrollText } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ClearFilters, FilterSelect, SearchBox } from '../components/filters';
import { PageHeader } from '../components/page-header';
import { Pagination } from '../components/pagination';
import { SortHead } from '../components/sort-head';
import { EmptyState, ErrorState, TableSkeleton } from '../components/states';
import { ActionBadge, formatDateTime } from '../components/status-badges';
import { useDebounced, useResource } from '../hooks/use-resource';
import { auditService, type AuditQuery } from '../services/audit-service';
import { AUDIT_ACTION_LABEL, AUDIT_ENTITY_LABEL, type AuditAction, type AuditEntity } from '../types';
import { AuditDetailDialog } from './audit-detail-dialog';

const PAGE_SIZE = 10;
type SortKey = NonNullable<AuditQuery['sort']>;

function DateField({ id, label, value, onChange, error }: { id: string; label: string; value: string; onChange: (v: string) => void; error?: boolean }) {
  return (
    <div className="flex items-center gap-2">
      <Label htmlFor={id} className="whitespace-nowrap text-muted-foreground">{label}</Label>
      <Input id={id} type="date" value={value} onChange={(e) => onChange(e.target.value)} aria-invalid={error} className="w-full sm:w-[150px]" />
    </div>
  );
}

export default function AuditLogsPage() {
  const [search, setSearch] = useState('');
  const [action, setAction] = useState<AuditAction | 'ALL'>('ALL');
  const [entity, setEntity] = useState<AuditEntity | 'ALL'>('ALL');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [sort, setSort] = useState<SortKey>('at');
  const [dir, setDir] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(1);
  const [openId, setOpenId] = useState<string | null>(null);

  const rangeError = from && to && from > to ? 'The end date must be on or after the start date.' : null;
  const debounced = useDebounced(search);
  useEffect(() => setPage(1), [debounced, action, entity, from, to]);

  const { data, loading, error, reload } = useResource(
    () => auditService.list({ search: debounced, action, entity, from: rangeError ? '' : from, to: rangeError ? '' : to, sort, dir, page, pageSize: PAGE_SIZE }),
    [debounced, action, entity, from, to, rangeError, sort, dir, page]
  );

  const items = data?.items ?? [];
  const filtersActive = !!(search || action !== 'ALL' || entity !== 'ALL' || from || to);
  const clear = () => { setSearch(''); setAction('ALL'); setEntity('ALL'); setFrom(''); setTo(''); };
  const sortBy = (k: SortKey) => { if (sort === k) setDir((d) => (d === 'asc' ? 'desc' : 'asc')); else { setSort(k); setDir(k === 'at' ? 'desc' : 'asc'); } setPage(1); };

  return (
    <>
      <PageHeader
        title="Audit logs"
        description="A permanent, read-only record of administrative changes. Entries are added automatically and can never be edited or deleted."
      />

      <Card>
        <div className="space-y-3 border-b p-4">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-center">
            <SearchBox value={search} onChange={setSearch} label="Search audit logs" placeholder="Search by actor, action, entity type, identifier or summary" />
            <div className="grid grid-cols-1 gap-2 sm:flex sm:flex-wrap sm:items-center">
              <FilterSelect label="Filter by action" value={action} onChange={(v) => setAction(v as AuditAction | 'ALL')} allLabel="All actions"
                options={(Object.keys(AUDIT_ACTION_LABEL) as AuditAction[]).map((a) => ({ value: a, label: AUDIT_ACTION_LABEL[a] }))} />
              <FilterSelect label="Filter by entity type" value={entity} onChange={(v) => setEntity(v as AuditEntity | 'ALL')} allLabel="All entity types"
                options={(Object.keys(AUDIT_ENTITY_LABEL) as AuditEntity[]).map((e) => ({ value: e, label: AUDIT_ENTITY_LABEL[e] }))} />
            </div>
          </div>
          <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center">
            <DateField id="audit-from" label="From" value={from} onChange={setFrom} error={!!rangeError} />
            <DateField id="audit-to" label="To" value={to} onChange={setTo} error={!!rangeError} />
            <span className="text-xs text-muted-foreground">Dates are in UTC.</span>
            <ClearFilters show={filtersActive} onClear={clear} />
          </div>
          {rangeError && <p role="alert" className="text-sm font-medium text-destructive">{rangeError}</p>}
        </div>

        {error && !data ? <ErrorState message={error} onRetry={reload} />
          : loading && !data ? <TableSkeleton label="Loading audit logs" />
          : items.length === 0 && !loading ? (
            filtersActive
              ? <EmptyState title="No audit entries match your filters" description="Try a different search term or date range, or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
              : <EmptyState icon={ScrollText} title="No audit entries yet" description="Changes made by administrators will appear here." />
          ) : (
            <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
              {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
              <Table>
                <TableHeader className="bg-muted/60">
                  <TableRow>
                    <SortHead k="at" sort={sort} dir={dir} onSort={sortBy}>When</SortHead>
                    <SortHead k="actor" sort={sort} dir={dir} onSort={sortBy}>Actor</SortHead>
                    <SortHead k="action" sort={sort} dir={dir} onSort={sortBy}>Action</SortHead>
                    <SortHead k="entity" sort={sort} dir={dir} onSort={sortBy}>Entity</SortHead>
                    <TableHead>Summary</TableHead>
                    <TableHead className="w-12"><span className="sr-only">Details</span></TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {items.map((e) => (
                    <TableRow key={e.id}>
                      <TableCell className="whitespace-nowrap text-muted-foreground"><time dateTime={e.at}>{formatDateTime(e.at)}</time></TableCell>
                      <TableCell className="whitespace-nowrap font-medium">{e.actor}</TableCell>
                      <TableCell><ActionBadge action={e.action} /></TableCell>
                      <TableCell>
                        <div className="whitespace-nowrap font-medium">{AUDIT_ENTITY_LABEL[e.entity]}</div>
                        <div className="font-mono text-xs text-muted-foreground">{e.entityId}</div>
                      </TableCell>
                      <TableCell className="min-w-[260px] max-w-[420px] text-sm">
                        {e.summary}
                        {e.seed && <Badge variant="outline" className="ml-2 align-middle">Demo data</Badge>}
                      </TableCell>
                      <TableCell>
                        <Button variant="ghost" size="icon" aria-label={`View details: ${e.summary}`} onClick={() => setOpenId(e.id)}><Eye aria-hidden="true" /></Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Pagination page={data?.page ?? 1} pageSize={PAGE_SIZE} total={data?.total ?? 0} onPage={setPage} noun="entries" />
            </div>
          )}
      </Card>

      <AuditDetailDialog entryId={openId} onClose={() => setOpenId(null)} />
    </>
  );
}
