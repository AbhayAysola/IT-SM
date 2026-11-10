'use client';

import React, { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FormError, withServerFields } from '../components/form-error';
import { FormField } from '../components/form-field';
import { useResource } from '../hooks/use-resource';
import { itServiceApi } from '../services/it-service-api';
import { SLA_DEFAULT_WARNING_PCT, slaApi, validateSla, type SlaForm } from '../services/sla-api';
import { PRIORITIES, PRIORITY_LABEL, ServiceError, type Priority, type SlaPolicy } from '../types';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  policy?: SlaPolicy | null;
  onSaved: (p: SlaPolicy, mode: 'created' | 'updated') => void;
}

const GLOBAL = 'GLOBAL';
const EMPTY: SlaForm = { serviceId: '', priority: 'P3', responseMinutes: '', resolutionMinutes: '', warningThresholdPct: String(SLA_DEFAULT_WARNING_PCT) };
const asError = (e: unknown) => (e instanceof ServiceError ? e : new ServiceError('UNAVAILABLE', 'Could not save the policy.'));
const toForm = (p: SlaPolicy): SlaForm => ({
  serviceId: p.service ? String(p.service.id) : '', priority: p.priority, responseMinutes: String(p.responseMinutes),
  resolutionMinutes: String(p.resolutionMinutes), warningThresholdPct: String(p.warningThresholdPct),
});

export function SlaFormDialog({ open, onOpenChange, policy, onSaved }: Props) {
  const editing = !!policy;
  const [v, setV] = useState<SlaForm>(EMPTY);
  const [version, setVersion] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ServiceError | null>(null);
  const [busy, setBusy] = useState(false);

  const services = useResource(() => (open && !editing ? itServiceApi.list({ active: 'true', sort: 'name', size: 100 }) : Promise.resolve(null)), [open, editing]);

  useEffect(() => {
    if (!open) return;
    setV(policy ? toForm(policy) : EMPTY); setVersion(policy?.version ?? 0); setErrors({}); setFailure(null);
  }, [open, policy]);

  const set = <K extends keyof SlaForm>(k: K, val: SlaForm[K]) => { setV((p) => ({ ...p, [k]: val })); setErrors((p) => ({ ...p, [k]: '' })); };

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const local = validateSla(v, editing ? 'edit' : 'create');
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true); setFailure(null);
    try {
      const saved = editing ? await slaApi.update(policy!.id, v, version) : await slaApi.create(v);
      onSaved(saved, editing ? 'updated' : 'created');
      onOpenChange(false);
    } catch (e) {
      const err = asError(e); setFailure(err); setErrors(withServerFields(local, err));
    } finally { setBusy(false); }
  };
  const keepMine = async () => {
    setBusy(true);
    try { setVersion((await slaApi.get(policy!.id)).version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };
  const loadLatest = async () => {
    setBusy(true);
    try { const p = await slaApi.get(policy!.id); setV(toForm(p)); setVersion(p.version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{editing ? 'Edit SLA policy' : 'Add SLA policy'}</DialogTitle>
          <DialogDescription>
            These are configured targets only. The warning threshold is the share of the target time after which monitoring will warn; nothing is enforced here.
            {editing && ' The service and priority identify a policy and cannot be changed.'}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-4">
          <FormError error={failure} onKeepMine={editing ? keepMine : undefined} onLoadLatest={editing ? loadLatest : undefined} busy={busy} />
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField id="sla-service" label="Applies to" error={errors.serviceId} hint={editing ? undefined : 'A global policy is the fallback for services without their own.'}>
              {editing ? (
                <Input id="sla-service" value={policy!.service ? policy!.service.name : 'Global default'} disabled />
              ) : (
                <Select value={v.serviceId === '' ? GLOBAL : v.serviceId} onValueChange={(x) => set('serviceId', x === GLOBAL ? '' : x)}>
                  <SelectTrigger id="sla-service"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    <SelectItem value={GLOBAL}>Global default</SelectItem>
                    {(services.data?.items ?? []).map((s) => <SelectItem key={s.id} value={String(s.id)}>{s.name}</SelectItem>)}
                  </SelectContent>
                </Select>
              )}
            </FormField>
            <FormField id="sla-priority" label="Priority" error={errors.priority}>
              {editing ? (
                <Input id="sla-priority" value={PRIORITY_LABEL[policy!.priority]} disabled />
              ) : (
                <Select value={v.priority} onValueChange={(x) => set('priority', x as Priority)}>
                  <SelectTrigger id="sla-priority"><SelectValue /></SelectTrigger>
                  <SelectContent>{PRIORITIES.map((p) => <SelectItem key={p} value={p}>{PRIORITY_LABEL[p]}</SelectItem>)}</SelectContent>
                </Select>
              )}
            </FormField>
          </div>
          {services.error && <p role="alert" className="text-sm text-destructive">{services.error}</p>}
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField id="sla-response" label="Response target (minutes)" error={errors.responseMinutes}>
              <Input id="sla-response" inputMode="numeric" value={v.responseMinutes} aria-invalid={!!errors.responseMinutes} aria-describedby={errors.responseMinutes ? 'sla-response-error' : undefined} onChange={(e) => set('responseMinutes', e.target.value)} />
            </FormField>
            <FormField id="sla-resolution" label="Resolution target (minutes)" error={errors.resolutionMinutes} hint="Longer than the response target, at most 43,200 (30 days).">
              <Input id="sla-resolution" inputMode="numeric" value={v.resolutionMinutes} aria-invalid={!!errors.resolutionMinutes} aria-describedby={errors.resolutionMinutes ? 'sla-resolution-error' : 'sla-resolution-hint'} onChange={(e) => set('resolutionMinutes', e.target.value)} />
            </FormField>
          </div>
          <FormField id="sla-warning" label="Warning threshold (%)" error={errors.warningThresholdPct} hint="1–99. Leave blank to use 80 (new) or keep the current value (edit).">
            <Input id="sla-warning" inputMode="numeric" value={v.warningThresholdPct} aria-invalid={!!errors.warningThresholdPct} aria-describedby={errors.warningThresholdPct ? 'sla-warning-error' : 'sla-warning-hint'} onChange={(e) => set('warningThresholdPct', e.target.value)} />
          </FormField>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
            <Button type="submit" disabled={busy}>{busy && <Loader2 className="animate-spin" aria-hidden="true" />}{editing ? 'Save changes' : 'Add policy'}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
