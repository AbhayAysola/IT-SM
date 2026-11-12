'use client';

import React, { useEffect, useState, type RefObject } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { ErrorState } from '@/features/admin/components/states';
import { FormField } from '@/features/admin/components/form-field';
import { useResource } from '@/features/admin/hooks/use-resource';
import { MAX_REASON_LENGTH } from '../model/lifecycle';
import { IncidentError, type Actor, type Incident } from '../model/types';
import { returnFocus } from './reason-dialog';
import { incidentService } from '../services/incident-service';

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  actor: Actor;
  incident: Pick<Incident, 'id' | 'title' | 'state' | 'category' | 'priority'>;
  onAssigned: (name: string) => void;
  restoreFocusTo?: RefObject<HTMLElement | null>;
}

/** Scored, explained suggestions (backend `TechnicianRecommender`); the admin makes the final choice. */
export function AssignDialog(props: Props) {
  // Mount the body only while open so it loads fresh recommendations every time.
  return (
    <Dialog open={props.open} onOpenChange={props.onOpenChange}>
      <DialogContent className="max-w-xl" onCloseAutoFocus={returnFocus(props.restoreFocusTo)}>{props.open && <Body {...props} />}</DialogContent>
    </Dialog>
  );
}

function Body({ onOpenChange, actor, incident, onAssigned }: Props) {
  const reassign = incident.state !== 'CLASSIFIED';
  const { data, loading, error, reload } = useResource(() => incidentService.recommendations(actor, incident.id), [incident.id]);
  const [choice, setChoice] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [reasonError, setReasonError] = useState<string | undefined>();

  useEffect(() => {
    // Pre-select the best match, but never override a choice the person already made.
    if (data && data.length > 0) setChoice((c) => c ?? data[0].technicianEmail);
  }, [data]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!choice) { setFormError('Choose a technician.'); return; }
    if (reassign && !reason.trim()) { setReasonError('Please say why the incident is moving.'); return; }
    setBusy(true); setFormError(null); setReasonError(undefined);
    try {
      await incidentService.assign(actor, incident.id, choice, reason);
      onAssigned(data?.find((r) => r.technicianEmail === choice)?.name ?? 'the technician');
      onOpenChange(false);
    } catch (err) {
      if (err instanceof IncidentError && err.fields?.reason) setReasonError(err.fields.reason);
      else setFormError(err instanceof Error ? err.message : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className="space-y-4">
      <DialogHeader>
        <DialogTitle>{reassign ? 'Reassign incident' : 'Assign technician'}</DialogTitle>
        <DialogDescription>
          {incident.id}: {incident.title}. Suggestions favour {incident.priority === 'P1' || incident.priority === 'P2' ? 'experience on urgent incidents' : 'free capacity on routine ones'} and skill in {incident.category}.
        </DialogDescription>
      </DialogHeader>

      {loading && !data ? (
        <p role="status" className="text-sm text-muted-foreground">Finding the best match…</p>
      ) : error ? (
        <ErrorState message={error} onRetry={reload} />
      ) : data && data.length === 0 ? (
        <p role="status" className="rounded-md border bg-muted/50 p-3 text-sm">
          Nobody is available right now: everyone is unavailable or at their workload limit. Free up capacity in Technicians, then try again.
        </p>
      ) : (
        <fieldset className="space-y-2">
          <legend className="mb-1.5 text-sm font-medium">Recommended technicians</legend>
          {data?.map((r, idx) => (
            <label key={r.technicianEmail} className="flex cursor-pointer items-start gap-3 rounded-lg border p-3 has-[:checked]:border-primary has-[:checked]:bg-primary/5 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring">
              <input type="radio" name="technician" value={r.technicianEmail} checked={choice === r.technicianEmail}
                onChange={() => setChoice(r.technicianEmail)} className="mt-1 h-4 w-4 accent-primary" />
              <span className="min-w-0 flex-1">
                <span className="flex flex-wrap items-baseline gap-x-2">
                  <span className="font-medium">{r.name}</span>
                  <span className="text-xs text-muted-foreground">{r.team}</span>
                  {idx === 0 && <span className="text-xs font-semibold text-primary">Best match</span>}
                  <span className="ml-auto text-sm font-semibold">Score {r.score}</span>
                </span>
                <span className="mt-1 block text-xs text-muted-foreground">{r.reasons.join(' · ')}</span>
              </span>
            </label>
          ))}
        </fieldset>
      )}

      {reassign && (
        <FormField id="reassign-reason" label="Why is it moving?" error={reasonError} hint="Internal only; the reporter does not see this.">
          <Textarea id="reassign-reason" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={MAX_REASON_LENGTH + 1}
            aria-invalid={!!reasonError} aria-describedby={reasonError ? 'reassign-reason-error' : 'reassign-reason-hint'} />
        </FormField>
      )}
      {formError && <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm font-medium text-destructive">{formError}</p>}

      <DialogFooter>
        <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
        <Button type="submit" disabled={busy || !data || data.length === 0}>
          {busy && <Loader2 className="animate-spin" aria-hidden="true" />}
          {reassign ? 'Reassign' : 'Assign'}
        </Button>
      </DialogFooter>
    </form>
  );
}
