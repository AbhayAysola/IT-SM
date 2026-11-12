'use client';

import React, { useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { FormField } from '@/features/admin/components/form-field';
import { useToast } from '@/features/admin/components/toast';
import { IncidentError, type Actor } from '../model/types';
import { incidentService, NOTE_MAX } from '../services/incident-service';

export function NoteForm({ actor, incidentId, onAdded }: { actor: Actor; incidentId: string; onAdded: () => void }) {
  const toast = useToast();
  const [body, setBody] = useState('');
  const [share, setShare] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true); setError(undefined);
    try {
      await incidentService.addNote(actor, incidentId, body, share);
      setBody(''); setShare(false);
      toast.success(share ? 'Note added and shared with the reporter.' : 'Note added.');
      onAdded();
    } catch (err) {
      if (err instanceof IncidentError && err.fields?.body) setError(err.fields.body);
      else toast.error(err instanceof Error ? err.message : 'Could not add the note.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} noValidate className="mt-4 space-y-3 border-t pt-4">
      <FormField id="note-body" label="Add a note" error={error} hint="Working notes stay internal unless you share them.">
        <Textarea id="note-body" value={body} onChange={(e) => setBody(e.target.value)} maxLength={NOTE_MAX + 1}
          aria-invalid={!!error} aria-describedby={error ? 'note-body-error' : 'note-body-hint'} />
      </FormField>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={share} onChange={(e) => setShare(e.target.checked)} className="h-4 w-4 accent-primary" />
          Share with the reporter
        </label>
        <Button type="submit" variant="outline" disabled={busy}>
          {busy && <Loader2 className="animate-spin" aria-hidden="true" />}
          Add note
        </Button>
      </div>
    </form>
  );
}
