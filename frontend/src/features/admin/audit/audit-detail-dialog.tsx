'use client';

import React from 'react';
import { ArrowRight, Lock } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { ErrorState } from '../components/states';
import { formatDateTime } from '../components/status-badges';
import { useResource } from '../hooks/use-resource';
import { auditApi } from '../services/audit-api';
import { actionTone, actorLabel, humanize, metadataValue } from './audit-types';

const fieldName = (k: string) => k.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/^./, (c) => c.toUpperCase());

function Detail({ id }: { id: string }) {
  const { data: e, loading, error, reload } = useResource(() => auditApi.get(id), [id]);
  if (error) return <ErrorState message={error} onRetry={reload} />;
  if (loading || !e) return <div role="status" aria-label="Loading audit entry" className="space-y-2"><Skeleton className="h-6" /><Skeleton className="h-24" /></div>;

  const keys = Object.keys(e.metadata ?? {});
  return (
    <div className="space-y-4">
      <p className="text-sm text-foreground">{e.description}</p>
      <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-2">
        <div><dt className="text-xs text-muted-foreground">When</dt><dd className="font-medium"><time dateTime={e.occurredAt}>{formatDateTime(e.occurredAt)}</time></dd></div>
        <div><dt className="text-xs text-muted-foreground">Actor</dt><dd className="font-medium">{actorLabel(e.actor)}</dd></div>
        <div><dt className="text-xs text-muted-foreground">Action</dt><dd><Badge variant={actionTone(e.action)}>{humanize(e.action)}</Badge> <span className="ml-1 font-mono text-xs text-muted-foreground">{e.action}</span></dd></div>
        <div><dt className="text-xs text-muted-foreground">Entity type</dt><dd className="font-medium">{humanize(e.entityType)}</dd></div>
        {e.entityId && <div><dt className="text-xs text-muted-foreground">Entity identifier</dt><dd className="font-mono text-xs">{e.entityId}</dd></div>}
        {e.incidentId && <div><dt className="text-xs text-muted-foreground">Incident</dt><dd className="font-mono text-xs">{e.incidentId}</dd></div>}
        {(e.previousState || e.newState) && (
          <div><dt className="text-xs text-muted-foreground">State change</dt>
            <dd className="flex items-center gap-1 font-medium">{e.previousState ? humanize(e.previousState) : '—'} <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" /> {e.newState ? humanize(e.newState) : '—'}</dd></div>
        )}
        <div><dt className="text-xs text-muted-foreground">Entry identifier</dt><dd className="break-all font-mono text-xs">{e.id}</dd></div>
      </dl>

      <section aria-label="Recorded details">
        <h3 className="mb-2 text-sm font-semibold">Recorded details</h3>
        {keys.length === 0 ? (
          <p className="rounded-md border bg-muted/50 p-3 text-sm text-muted-foreground" data-testid="no-metadata">No additional details were recorded for this entry.</p>
        ) : (
          <div className="rounded-md border">
            <Table data-testid="audit-metadata">
              <TableHeader className="bg-muted/60"><TableRow><TableHead>Field</TableHead><TableHead>Value</TableHead></TableRow></TableHeader>
              <TableBody>
                {keys.map((k) => (
                  <TableRow key={k}>
                    <TableCell className="font-medium">{fieldName(k)}</TableCell>
                    <TableCell className="break-words">{metadataValue(e.metadata[k])}</TableCell>
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
