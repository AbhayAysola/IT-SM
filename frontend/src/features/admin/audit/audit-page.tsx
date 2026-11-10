'use client';

import React, { useEffect, useState } from 'react';
import { Eye, ScrollText } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ClearFilters, FilterSelect } from '../components/filters';
import { PageHeader } from '../components/page-header';
import { Pagination } from '../components/pagination';
import { SortHead } from '../components/sort-head';
import { EmptyState, ErrorState, TableSkeleton } from '../components/states';
import { formatDateTime } from '../components/status-badges';
import { useDebounced, useResource } from '../hooks/use-resource';
import { auditApi } from '../services/audit-api';
import { ServiceError } from '../types';
import {
  actionTone, actorLabel, EMPTY_AUDIT_FILTERS, EMPTY_AUDIT_PAGE, humanize, ROLE_FILTER_LABEL, validateFilters,
  type AuditFilters, type AuditPage, type AuditRoleFilter, type AuditSortKey,
} from './audit-types';
import { AuditDetailDialog } from './audit-detail-dialog';

const PAGE_SIZE = 10;

function TextFilter({ id, label, value, onChange, error, placeholder, className }: {
  id: string; label: string; value: string; onChange: (v: string) => void; error?: string; placeholder?: string; className?: string;
}) {
  return (
    <div className={className}>
      <Label htmlFor={id} className="mb-1 block text-xs text-muted-foreground">{label}</Label>
      <Input id={id} value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined} autoComplete="off" spellCheck={false} />
      {error && <p id={`${id}-error`} role="alert" className="mt-1 text-xs font-medium text-destructive">{error}</p>}
    </div>
  );
}

export default function AuditLogsPage() {
  const [filters, setFilters] = useState<AuditFilters>(EMPTY_AUDIT_FILTERS);
  const [sort, setSort] = useState<AuditSortKey>('occurredAt');
  const [dir, setDir] = useState<'asc' | 'desc'>('desc');
  const [page, setPage] = useState(1);
  const [openId, setOpenId] = useState<string | null>(null);
  // Set when the backend says this caller may not read the log: everything already on screen is dropped.
  const [denied, setDenied] = useState<string | null>(null);

  const set = <K extends keyof AuditFilters>(k: K, v: AuditFilters[K]) => setFilters((f) => ({ ...f, [k]: v }));
  const errors = validateFilters(filters);
  const invalid = Object.keys(errors).length > 0;
  const debounced = useDebounced(filters);
  useEffect(() => setPage(1), [debounced]);

  const { data, loading, error, reload } = useResource(
    (): Promise<AuditPage> => {
      // Invalid filters are never sent (and never silently dropped): the form explains what to fix instead.
      if (Object.keys(validateFilters(debounced)).length > 0) return Promise.resolve(EMPTY_AUDIT_PAGE);
      return auditApi.list({ filters: debounced, sort, dir, page, pageSize: PAGE_SIZE }).then(
        (d) => { setDenied(null); return d; },
        (e: unknown) => {
          if (e instanceof ServiceError && (e.code === 'FORBIDDEN' || e.code === 'UNAUTHENTICATED')) setDenied(e.message);
          throw e;
        }
      );
    },
    [debounced, sort, dir, page]
  );

  const items = data?.items ?? [];
  const filtersActive = JSON.stringify(filters) !== JSON.stringify(EMPTY_AUDIT_FILTERS);
  const clear = () => setFilters(EMPTY_AUDIT_FILTERS);
  const sortBy = (k: AuditSortKey) => { if (sort === k) setDir((d) => (d === 'asc' ? 'desc' : 'asc')); else { setSort(k); setDir(k === 'occurredAt' ? 'desc' : 'asc'); } setPage(1); };

  return (
    <>
      <PageHeader
        title="Audit logs"
        description="A permanent, read-only record of administrative and system actions. Entries are added automatically and can never be edited or deleted."
      />

      <Card>
        {denied ? (
          <ErrorState message={denied} />
        ) : (
          <>
            <div className="space-y-3 border-b p-4">
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <TextFilter id="audit-action" label="Action" value={filters.action} onChange={(v) => set('action', v)} error={errors.action} placeholder="e.g. USER_CREATED" />
                <TextFilter id="audit-entity-type" label="Entity type" value={filters.entityType} onChange={(v) => set('entityType', v)} error={errors.entityType} placeholder="e.g. SLA_POLICY" />
                <TextFilter id="audit-entity-id" label="Entity ID" value={filters.entityId} onChange={(v) => set('entityId', v)} error={errors.entityId} placeholder="Exact identifier" />
                <TextFilter id="audit-actor-id" label="Actor ID" value={filters.actorId} onChange={(v) => set('actorId', v)} error={errors.actorId} placeholder="User number" />
              </div>
              <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-start">
                <div>
                  <span className="mb-1 block text-xs text-muted-foreground">Actor role</span>
                  <FilterSelect label="Filter by actor role" value={filters.actorRole} onChange={(v) => set('actorRole', v as AuditRoleFilter)} allLabel="All actors"
                    options={(Object.keys(ROLE_FILTER_LABEL) as (keyof typeof ROLE_FILTER_LABEL)[]).map((r) => ({ value: r, label: ROLE_FILTER_LABEL[r] }))} />
                </div>
                <TextDate id="audit-from" label="From" value={filters.from} onChange={(v) => set('from', v)} error={errors.from} />
                <TextDate id="audit-to" label="To" value={filters.to} onChange={(v) => set('to', v)} error={errors.to} />
                <div className="flex items-center gap-2 sm:mt-5">
                  <span className="text-xs text-muted-foreground">Dates are in UTC.</span>
                  <ClearFilters show={filtersActive} onClear={clear} />
                </div>
              </div>
            </div>

            {invalid ? (
              <EmptyState title="Fix the highlighted filters" description="Results will appear as soon as every filter is valid." />
            ) : error && !data ? <ErrorState message={error} onRetry={reload} />
              : loading && !data ? <TableSkeleton label="Loading audit logs" />
              : items.length === 0 && !loading ? (
                filtersActive
                  ? <EmptyState title="No audit entries match your filters" description="Try different filters or a wider date range, or clear the filters." action={<Button variant="outline" size="sm" onClick={clear}>Clear filters</Button>} />
                  : <EmptyState icon={ScrollText} title="No audit entries yet" description="Actions recorded by the system will appear here." />
              ) : (
                <div className={loading ? 'opacity-60 transition-opacity' : undefined} aria-busy={loading}>
                  {error && <div className="border-b"><ErrorState message={error} onRetry={reload} /></div>}
                  <Table>
                    <TableHeader className="bg-muted/60">
                      <TableRow>
                        <SortHead k="occurredAt" sort={sort} dir={dir} onSort={sortBy}>When</SortHead>
                        <SortHead k="actorRole" sort={sort} dir={dir} onSort={sortBy}>Actor</SortHead>
                        <SortHead k="action" sort={sort} dir={dir} onSort={sortBy}>Action</SortHead>
                        <SortHead k="entityType" sort={sort} dir={dir} onSort={sortBy}>Entity</SortHead>
                        <TableHead>Summary</TableHead>
                        <TableHead className="w-12"><span className="sr-only">Details</span></TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {items.map((e) => (
                        <TableRow key={e.id}>
                          <TableCell className="whitespace-nowrap text-muted-foreground"><time dateTime={e.occurredAt}>{formatDateTime(e.occurredAt)}</time></TableCell>
                          <TableCell className="whitespace-nowrap font-medium">{actorLabel(e.actor)}</TableCell>
                          <TableCell><Badge variant={actionTone(e.action)}>{humanize(e.action)}</Badge></TableCell>
                          <TableCell>
                            <div className="whitespace-nowrap font-medium">{humanize(e.entityType)}</div>
                            {e.entityId && <div className="font-mono text-xs text-muted-foreground">{e.entityId}</div>}
                            {e.incidentId && e.incidentId !== e.entityId && <div className="font-mono text-xs text-muted-foreground">Incident {e.incidentId}</div>}
                          </TableCell>
                          <TableCell className="min-w-[260px] max-w-[420px] text-sm">{e.description}</TableCell>
                          <TableCell>
                            <Button variant="ghost" size="icon" aria-label={`View details: ${e.description}`} onClick={() => setOpenId(e.id)}><Eye aria-hidden="true" /></Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                  <Pagination page={(data?.page ?? 0) + 1} pageSize={PAGE_SIZE} total={data?.totalItems ?? 0} onPage={setPage} noun="entries" />
                </div>
              )}
          </>
        )}
      </Card>

      <AuditDetailDialog entryId={openId} onClose={() => setOpenId(null)} />
    </>
  );
}

function TextDate({ id, label, value, onChange, error }: { id: string; label: string; value: string; onChange: (v: string) => void; error?: string }) {
  return (
    <div>
      <Label htmlFor={id} className="mb-1 block text-xs text-muted-foreground">{label}</Label>
      <Input id={id} type="date" value={value} onChange={(e) => onChange(e.target.value)} aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined} className="w-full sm:w-[160px]" />
      {error && <p id={`${id}-error`} role="alert" className="mt-1 text-xs font-medium text-destructive">{error}</p>}
    </div>
  );
}
