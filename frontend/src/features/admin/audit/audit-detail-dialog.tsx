'use client';

import React from 'react';
import { Lock } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ErrorState } from '../components/states';
import { ActionBadge, formatDateTime } from '../components/status-badges';
import { useResource } from '../hooks/use-resource';
import { auditService } from '../services/audit-service';
import { AUDIT_ACTION_LABEL, AUDIT_ENTITY_LABEL } from '../types';

const fieldName = (k: string) => k.replace(/\./g, ' › ').replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase());

function Detail({ id }: { id: string }) {
  const { data: e, loading, error, reload } = useResource(() => auditService.get(id), [id]);
  if (error) return <ErrorState message={error} onRetry={reload} />;
  if (loading || !e) return <div role="status" aria-label="Loading audit entry" className="space-y-2"><Skeleton className="h-6" /><Skeleton className="h-24" /></div>;

  const keys = Object.keys(e.after ?? {});
  return (
    <div className="space-y-4">
      <p className="text-sm text-foreground">{e.summary}</p>
      <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
        <div><dt className="text-xs text-muted-foreground">When</dt><dd className="font-medium"><time dateTime={e.at}>{formatDateTime(e.at)}</time></dd></div>
        <div><dt className="text-xs text-muted-foreground">Actor</dt><dd className="font-medium">{e.actor}</dd></div>
        <div><dt className="text-xs text-muted-foreground">Action</dt><dd><ActionBadge action={e.action} /></dd></div>
        <div><dt className="text-xs text-muted-foreground">Entity type</dt><dd className="font-medium">{AUDIT_ENTITY_LABEL[e.entity]}</dd></div>
        <div><dt className="text-xs text-muted-foreground">Entity identifier</dt><dd className="font-mono text-xs">{e.entityId}</dd></div>
        <div><dt className="text-xs text-muted-foreground">Entry identifier</dt><dd className="font-mono text-xs">{e.id}</dd></div>
        <div className="sm:col-span-2"><dt className="text-xs text-muted-foreground">Source</dt><dd>{e.seed ? <Badge variant="outline">Demo data</Badge> : <span className="font-medium">Recorded from an administrator action</span>}</dd></div>
      </dl>

      <section aria-label="Changes">
        <h3 className="mb-2 text-sm font-semibold">Changes</h3>
        {keys.length === 0 ? (
          <p className="rounded-md border bg-muted/50 p-3 text-sm text-muted-foreground" data-testid="no-changes">
            No before/after values were recorded for this entry{e.action === 'CREATE' || e.action === 'DELETE' ? ` (${AUDIT_ACTION_LABEL[e.action].toLowerCase()} actions record the summary only)` : ''}.
          </p>
        ) : (
          <div className="rounded-md border">
            <Table data-testid="audit-changes">
              <TableHeader className="bg-muted/60"><TableRow><TableHead>Field</TableHead><TableHead>Before</TableHead><TableHead>After</TableHead></TableRow></TableHeader>
              <TableBody>
                {keys.map((k) => (
                  <TableRow key={k}>
                    <TableCell className="font-medium">{fieldName(k)}</TableCell>
                    <TableCell className="text-muted-foreground">{e.before?.[k] ?? '—'}</TableCell>
                    <TableCell>{e.after?.[k]}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </section>
    </div>
  );
}

export function AuditDetailDialog({ entryId, onClose }: { entryId: string | null; onClose: () => void }) {
  return (
    <Dialog open={!!entryId} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Audit entry</DialogTitle>
          <DialogDescription className="flex items-center gap-1.5"><Lock className="h-3.5 w-3.5" aria-hidden="true" /> Audit entries are read-only and cannot be edited or deleted.</DialogDescription>
        </DialogHeader>
        {entryId && <Detail id={entryId} />}
        <DialogFooter><Button variant="outline" onClick={onClose}>Close</Button></DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
