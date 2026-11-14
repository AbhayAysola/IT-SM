'use client';

import React, { useRef, useState } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { ArrowLeft } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card';
import { CardHeading } from './components/card-heading';
import { Skeleton } from '@/components/ui/skeleton';
import { ErrorState } from '@/features/admin/components/states';
import { useToast } from '@/features/admin/components/toast';
import { useResource } from '@/features/admin/hooks/use-resource';
import { AssignDialog } from './components/assign-dialog';
import { formatDay, formatWhen, PriorityBadge, StateBadge } from './components/badges';
import { FeedbackCard } from './components/feedback-card';
import { NoteForm } from './components/note-form';
import { ReasonDialog } from './components/reason-dialog';
import { SimilarPanel } from './components/similar-panel';
import { SlaPanel } from './components/sla-panel';
import { Timeline } from './components/timeline';
import { useActor } from './components/workspace-shell';
import { TRANSITION_LABEL, WORKLOAD_STATES as WORK_IN_PROGRESS } from './model/lifecycle';
import { IncidentError, type TransitionName } from './model/types';
import { incidentService } from './services/incident-service';

/** Low-risk moves with nothing to say happen on one click; the rest go through a dialog. */
const DIRECT: TransitionName[] = ['ACKNOWLEDGE', 'START_WORK'];
const SUCCESS: Partial<Record<TransitionName, string>> = {
  ACKNOWLEDGE: 'Incident accepted.', START_WORK: 'Investigation started.', REQUEST_INFO: 'Your question was sent to the reporter.',
  PROVIDE_INFO: 'Your reply was sent.', RESOLVE: 'Marked as resolved. The reporter will be asked to confirm.', VERIFY: 'Thanks, the fix is confirmed.',
  REOPEN: 'The incident was reopened.', CLOSE: 'Incident closed.',
};

export default function IncidentDetailPage() {
  const actor = useActor();
  const toast = useToast();
  const params = useParams<{ id: string }>();
  const id = decodeURIComponent(params.id);
  const { data, loading, error, reload } = useResource(() => incidentService.get(actor, id), [actor.email, id]);
  // `dialog` is kept after closing so the dialog stays mounted: Radix returns focus to the opener on close.
  const [dialog, setDialog] = useState<TransitionName | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  const opener = useRef<HTMLElement | null>(null);
  const openDialog = (t: TransitionName) => {
    opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setDialog(t);
    setDialogOpen(true);
  };
  const [busyAction, setBusyAction] = useState<TransitionName | null>(null);

  const back = (
    <Link href="/incidents" className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-primary underline-offset-4 hover:underline">
      <ArrowLeft className="h-4 w-4" aria-hidden="true" /> All incidents
    </Link>
  );

  if (loading && !data) {
    return (
      <>
        {back}
        <div role="status" aria-label="Loading incident" className="space-y-3">
          <Skeleton className="h-9 w-2/3" />
          <Skeleton className="h-40 w-full" />
        </div>
      </>
    );
  }
  if (error || !data) {
    const notFound = error?.includes('couldn’t find');
    return (
      <>
        {back}
        {notFound ? (
          <div role="alert" className="rounded-lg border bg-card p-8 text-center">
            <h1 className="text-xl font-semibold">Incident not found</h1>
            <p className="mt-1 text-sm text-muted-foreground">It may not exist, or it may belong to someone else.</p>
          </div>
        ) : (
          <ErrorState message={error ?? 'Something went wrong.'} onRetry={reload} />
        )}
      </>
    );
  }

  const { incident: i, audience, timeline, feedback, actions, canAddNote, canGiveFeedback, similar, sla, escalations } = data;
  const label = (t: TransitionName) => TRANSITION_LABEL[t][audience];
  const perform = async (t: TransitionName, reason = '') => {
    await incidentService.perform(actor, i.id, t, reason);
    toast.success(SUCCESS[t] ?? 'Done.');
    reload();
  };
  const direct = async (t: TransitionName) => {
    setBusyAction(t);
    try { await perform(t); } catch (e) {
      toast.error(e instanceof Error ? e.message : 'That did not work.');
      if (e instanceof IncidentError && e.code === 'CONFLICT') reload();
    } finally { setBusyAction(null); }
  };

  // Past fixes help while someone is working the incident, so only staff on an active one (the service sends none otherwise).
  const showSimilar = audience === 'staff' && WORK_IN_PROGRESS.includes(i.state);
  const assignAction = actions.find((a) => a === 'ASSIGN' || a === 'REASSIGN');
  const others = actions.filter((a) => a !== 'ASSIGN' && a !== 'REASSIGN');
  const primary = others.find((a) => a !== 'REOPEN') ?? assignAction;

  return (
    <>
      {back}
      <div className="mb-6">
        <div className="flex items-start justify-between gap-4">
          <h1 className="text-2xl font-semibold tracking-tight">{i.title}</h1>
          {(i.state === 'NEW' || i.state === 'CLASSIFIED') && (actor.role === 'ADMIN' || i.reporterEmail === actor.email) && (
            <Link href={`/incidents/${i.id}/edit`} className="shrink-0">
              <Button variant="outline" size="sm">Edit</Button>
            </Link>
          )}
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-2 text-sm">
          <span className="font-mono text-xs text-muted-foreground">{i.id}</span>
          <PriorityBadge priority={i.priority} />
          <StateBadge state={i.state} audience={audience} />
          <span className="text-muted-foreground">{i.category} · {i.service}</span>
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <Card>
            <CardHeader><CardHeading>What happened</CardHeading></CardHeader>
            <CardContent><p className="whitespace-pre-wrap break-words text-sm">{i.description}</p></CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardHeading>Investigation</CardHeading>
              <CardDescription>
                {audience === 'staff' ? 'Everything written on this incident. Internal entries are hidden from the reporter.' : 'The conversation with the technician.'}
              </CardDescription>
            </CardHeader>
            <CardContent>
              <Timeline entries={timeline} audience={audience} />
              {canAddNote && <NoteForm actor={actor} incidentId={i.id} onAdded={reload} />}
            </CardContent>
          </Card>
        </div>

        <div className="space-y-4">
          <Card>
            <CardHeader><CardHeading>Details</CardHeading></CardHeader>
            <CardContent>
              <dl className="space-y-3 text-sm">
                <div><dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Reported by</dt><dd className="mt-0.5">{i.reporterName}</dd></div>
                <div><dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Technician</dt><dd className="mt-0.5">{i.assigneeName ?? 'Not assigned yet'}</dd></div>
                <div><dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Reported</dt><dd className="mt-0.5">{formatDay(i.createdAt)}</dd></div>
                <div><dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Last update</dt><dd className="mt-0.5">{formatWhen(i.updatedAt)}</dd></div>
              </dl>
            </CardContent>
          </Card>

          {sla && <SlaPanel sla={sla} escalations={escalations} />}

          {(actions.length > 0) && (
            <Card>
              <CardHeader><CardHeading>{audience === 'staff' ? 'Next step' : 'What you can do'}</CardHeading></CardHeader>
              <CardContent className="flex flex-col gap-2">
                {assignAction && (
                  <Button variant={primary === assignAction ? 'default' : 'outline'} onClick={() => openDialog(assignAction)}>{label(assignAction)}</Button>
                )}
                {others.map((t) => (
                  <Button key={t} variant={t === primary ? 'default' : 'outline'} disabled={busyAction === t}
                    onClick={() => (DIRECT.includes(t) ? direct(t) : openDialog(t))}>
                    {label(t)}
                  </Button>
                ))}
              </CardContent>
            </Card>
          )}

          <FeedbackCard actor={actor} incidentId={i.id} feedback={feedback} canGive={canGiveFeedback} onGiven={reload} />
          {showSimilar && <SimilarPanel items={similar} />}
        </div>
      </div>

      {dialog && dialog !== 'ASSIGN' && dialog !== 'REASSIGN' && (
        <ReasonDialog open={dialogOpen} onOpenChange={setDialogOpen} transition={dialog} audience={audience} restoreFocusTo={opener} onConfirm={(r) => perform(dialog, r)} />
      )}
      {(dialog === 'ASSIGN' || dialog === 'REASSIGN') && (
        <AssignDialog open={dialogOpen} onOpenChange={setDialogOpen} actor={actor} incident={i} restoreFocusTo={opener}
          onAssigned={(name) => { toast.success(`Assigned to ${name}.`); reload(); }} />
      )}
    </>
  );
}
