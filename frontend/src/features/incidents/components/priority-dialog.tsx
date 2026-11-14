'use client';

import React, { useEffect, useState, type RefObject } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { FormField } from '@/features/admin/components/form-field';
import { MAX_PRIORITY_REASON, PRIORITIES, PRIORITY_NAME, priorityChangeErrors } from '../model/priority';
import { IncidentError, type Priority } from '../model/types';
import { returnFocus } from './reason-dialog';

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  current: Priority;
  /** Preselected choice (e.g. a suggestion); none by default. */
  suggested?: Priority;
  /** Prefilled reason (e.g. a suggestion's), which the admin can edit. */
  defaultReason?: string;
  /** May throw; an IncidentError's field errors are shown beside the fields and the dialog stays open. */
  onConfirm: (priority: Priority, reason: string) => Promise<void>;
  restoreFocusTo?: RefObject<HTMLElement | null>;
}

/**
 * Changes an incident's priority (admins), with a reason kept in the audit trail. The SLA deadlines follow the new
 * priority and the assignee is told (P4), so the dialog says so before anyone confirms.
 */
export function PriorityDialog({ open, onOpenChange, current, suggested, defaultReason, onConfirm, restoreFocusTo }: Props) {
  const [choice, setChoice] = useState<Priority | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});

  useEffect(() => {
    if (open) { setChoice(suggested ?? null); setReason(defaultReason ?? ''); setError(null); setFields({}); }
  }, [open, suggested, defaultReason]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    const errors = priorityChangeErrors(current, choice, reason);
    if (errors) { setFields(errors); return; }
    setBusy(true); setError(null); setFields({});
    try {
      await onConfirm(choice!, reason.trim());
      onOpenChange(false);
    } catch (err) {
      if (err instanceof IncidentError && err.fields && (err.fields.priority || err.fields.reason)) setFields(err.fields);
      else setError(err instanceof Error ? err.message : 'Something went wrong.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => { if (!busy) onOpenChange(o); }}>
      <DialogContent onCloseAutoFocus={returnFocus(restoreFocusTo)}>
        <form onSubmit={submit} noValidate className="space-y-4">
          <DialogHeader>
            <DialogTitle>Change priority</DialogTitle>
            <DialogDescription>
              It is {PRIORITY_NAME[current]} now. The SLA deadlines follow the new priority from this moment, and the assigned technician is told.
            </DialogDescription>
          </DialogHeader>
          <fieldset aria-describedby={fields.priority ? 'priority-error' : undefined}>
            <legend className="mb-2 text-sm font-medium">New priority</legend>
            <div className="grid grid-cols-2 gap-2">
              {PRIORITIES.map((p) => (
                <label key={p} className={`flex cursor-pointer items-center gap-2 rounded-md border px-3 py-2 text-sm ${choice === p ? 'border-primary bg-primary/5' : ''} ${p === current ? 'text-muted-foreground' : ''}`}>
                  <input type="radio" name="priority" value={p} checked={choice === p} onChange={() => setChoice(p)} className="accent-primary" />
                  {PRIORITY_NAME[p]}{p === current && <span className="text-xs">(current)</span>}
                </label>
              ))}
            </div>
            {fields.priority && <p id="priority-error" className="mt-1.5 text-sm font-medium text-destructive">{fields.priority}</p>}
          </fieldset>
          <FormField id="priority-reason" label="Why?" error={fields.reason} hint="Kept in the audit trail. For example: the whole finance team is now affected.">
            <Textarea
              id="priority-reason" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={MAX_PRIORITY_REASON + 1}
              aria-invalid={!!fields.reason} aria-describedby={fields.reason ? 'priority-reason-error' : 'priority-reason-hint'} autoComplete="off"
            />
          </FormField>
          {error && <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm font-medium text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2 className="animate-spin" aria-hidden="true" />}
              Change priority
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
