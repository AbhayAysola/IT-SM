'use client';

import React, { useEffect, useState, type RefObject } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Textarea } from '@/components/ui/textarea';
import { FormField } from '@/features/admin/components/form-field';
import { MAX_REASON_LENGTH, TRANSITION_LABEL, TRANSITIONS } from '../model/lifecycle';
import { IncidentError, type Audience, type TransitionName } from '../model/types';

const PROMPT: Partial<Record<TransitionName, { label: string; hint: string; description: string }>> = {
  REQUEST_INFO: { label: 'Your question', hint: 'The reporter will see this and the incident waits until they reply.', description: 'Ask the reporter for what you need. Work pauses until they answer.' },
  PROVIDE_INFO: { label: 'Your reply', hint: 'Answer the question as clearly as you can.', description: 'Your reply goes to the technician and work continues.' },
  RESOLVE: { label: 'How was it fixed?', hint: 'The reporter reads this when deciding whether it is fixed. Plain words please.', description: 'Describe the fix. The reporter will be asked to confirm it.' },
  REOPEN: { label: 'What is still wrong?', hint: 'The same technician will pick it up again.', description: 'Tell the technician what still does not work.' },
  REASSIGN: { label: 'Why is it moving?', hint: 'Internal only; the reporter does not see this.', description: 'Choose a new technician and say why.' },
  VERIFY: { label: '', hint: '', description: 'You are confirming the problem is solved. You can then rate how it was handled.' },
  CLOSE: { label: '', hint: '', description: 'Closing ends the incident. It cannot be reopened afterwards.' },
};

/** Puts focus back on the opener when the dialog closes, if it is still on the page. */
export const returnFocus = (ref?: RefObject<HTMLElement | null>) => (e: Event) => {
  const el = ref?.current;
  if (el && el.isConnected) { e.preventDefault(); el.focus(); }
};

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  transition: TransitionName;
  audience: Audience;
  /** May throw; an IncidentError's field errors are shown beside the field and the dialog stays open. */
  onConfirm: (reason: string) => Promise<void>;
  /** The control that opened the dialog. Radix only restores focus to its own DialogTrigger, so this does it. */
  restoreFocusTo?: RefObject<HTMLElement | null>;
}

export function ReasonDialog({ open, onOpenChange, transition, audience, onConfirm, restoreFocusTo }: Props) {
  const rule = TRANSITIONS[transition];
  const prompt = PROMPT[transition];
  const takesText = rule.requiresReason;
  const title = TRANSITION_LABEL[transition][audience];
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<string | undefined>();

  useEffect(() => {
    if (open) { setText(''); setError(null); setFieldError(undefined); }
  }, [open]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (takesText && !text.trim()) { setFieldError('Please write something first.'); return; }
    setBusy(true); setError(null); setFieldError(undefined);
    try {
      await onConfirm(text);
      onOpenChange(false);
    } catch (err) {
      if (err instanceof IncidentError && err.fields?.reason) setFieldError(err.fields.reason);
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
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>{prompt?.description ?? 'Please confirm.'}</DialogDescription>
          </DialogHeader>
          {takesText && (
            <FormField id="reason" label={prompt?.label ?? 'Explanation'} error={fieldError} hint={prompt?.hint}>
              <Textarea
                id="reason" value={text} onChange={(e) => setText(e.target.value)} maxLength={MAX_REASON_LENGTH + 1}
                aria-invalid={!!fieldError} aria-describedby={fieldError ? 'reason-error' : 'reason-hint'} autoComplete="off"
              />
            </FormField>
          )}
          {error && <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm font-medium text-destructive">{error}</p>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2 className="animate-spin" aria-hidden="true" />}
              {title}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
