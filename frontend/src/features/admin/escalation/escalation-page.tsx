'use client';

import React, { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { FormError, withServerFields } from '../components/form-error';
import { FormField } from '../components/form-field';
import { PageHeader } from '../components/page-header';
import { ErrorState } from '../components/states';
import { formatDateTime } from '../components/status-badges';
import { useToast } from '../components/toast';
import { useResource } from '../hooks/use-resource';
import { escalationApi, validateEscalation, type EscalationForm } from '../services/escalation-api';
import { ServiceError, type EscalationSettings } from '../types';

const asError = (e: unknown) => (e instanceof ServiceError ? e : new ServiceError('UNAVAILABLE', 'Could not save the settings.'));
const toForm = (s: EscalationSettings): EscalationForm => ({
  unassignedMinutes: String(s.unassignedMinutes), secondLevelPct: String(s.secondLevelPct), adminLevelPct: String(s.adminLevelPct),
});
const sameForm = (a: EscalationForm, b: EscalationForm) =>
  a.unassignedMinutes.trim() === b.unassignedMinutes.trim() && a.secondLevelPct.trim() === b.secondLevelPct.trim() && a.adminLevelPct.trim() === b.adminLevelPct.trim();

const TITLE = 'Escalation settings';

/**
 * When the escalation monitor sends an incident up the reporting line (P4, docs/architecture/p4.md section 3). The
 * breach itself always goes to the team lead; these settings decide the later steps.
 */
export default function EscalationSettingsPage() {
  const toast = useToast();
  const settings = useResource(() => escalationApi.get(), []);
  const saved = settings.data;

  const [form, setForm] = useState<EscalationForm | null>(null);
  const [version, setVersion] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ServiceError | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { if (saved) { setForm(toForm(saved)); setVersion(saved.version); } }, [saved]);

  if (settings.error && !saved) return (<><PageHeader title={TITLE} /><Card><ErrorState message={settings.error} onRetry={settings.reload} /></Card></>);
  if (!saved || !form) return (
    <>
      <PageHeader title={TITLE} />
      <div role="status" aria-label="Loading escalation settings"><Skeleton className="h-72" /></div>
    </>
  );

  const dirty = !sameForm(form, toForm(saved));
  const set = (patch: Partial<EscalationForm>) => { setForm({ ...form, ...patch }); setErrors({}); };

  const save = async () => {
    const local = validateEscalation(form);
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true); setFailure(null);
    try {
      const s = await escalationApi.update(form, version);
      toast.success('Escalation settings were saved.');
      setForm(toForm(s)); setVersion(s.version);
      settings.reload();
    } catch (e) {
      const err = asError(e); setFailure(err); setErrors(withServerFields(local, err));
    } finally { setBusy(false); }
  };
  const keepMine = async () => {
    setBusy(true);
    try { setVersion((await escalationApi.get()).version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };
  const loadLatest = async () => {
    setBusy(true);
    try { const s = await escalationApi.get(); setForm(toForm(s)); setVersion(s.version); setFailure(null); settings.reload(); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };

  const field = (id: keyof EscalationForm, label: string, hint: string) => (
    <FormField id={`esc-${id}`} label={label} error={errors[id]} hint={hint}>
      <Input id={`esc-${id}`} inputMode="numeric" value={form[id]} disabled={busy} aria-invalid={!!errors[id]}
        aria-describedby={errors[id] ? `esc-${id}-error` : `esc-${id}-hint`} onChange={(e) => set({ [id]: e.target.value })} />
    </FormField>
  );

  return (
    <>
      <PageHeader title={TITLE}
        description="When an incident is escalated beyond its technician. A breached SLA always goes to the technician's team lead; these settings control the later steps. Escalation notifies people and never reassigns the incident." />
      <Card>
        <CardHeader>
          <CardTitle className="text-lg">When to escalate</CardTitle>
          <CardDescription>
            Last changed {saved.updatedAt ? formatDateTime(saved.updatedAt) : 'never'}{saved.updatedByUserId !== null ? ` by user #${saved.updatedByUserId}` : ' (default settings)'}.
            Changes apply from the next check, within a minute.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          <FormError error={failure} onKeepMine={keepMine} onLoadLatest={loadLatest} busy={busy} />
          <div className="grid gap-4 sm:grid-cols-3">
            {field('unassignedMinutes', 'Unassigned for (minutes)', 'An incident nobody has picked up goes to the administrators after this long.')}
            {field('secondLevelPct', 'Next level at (% of target)', 'An open incident goes one level above the team lead when this much of its resolution target is used.')}
            {field('adminLevelPct', 'Administrators at (% of target)', 'And to the administrators at this point. Must be higher than the next level.')}
          </div>
          <div className="flex flex-wrap justify-end gap-2 border-t pt-4">
            <Button variant="outline" disabled={busy || !dirty} onClick={() => { setForm(toForm(saved)); setErrors({}); setFailure(null); }}>Discard changes</Button>
            <Button disabled={busy || !dirty} onClick={save}>{busy && <Loader2 className="animate-spin" aria-hidden="true" />} Save settings</Button>
          </div>
        </CardContent>
      </Card>
    </>
  );
}
