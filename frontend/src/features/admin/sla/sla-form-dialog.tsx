'use client';

import React, { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FormField } from '../components/form-field';
import { formatMinutes } from '../format';
import { useResource } from '../hooks/use-resource';
import { itServiceService } from '../services/service-service';
import { slaService, validateSlaInput } from '../services/sla-service';
import { PRIORITIES, PRIORITY_LABEL, type Priority, type SlaPolicyView } from '../types';

type Unit = 'min' | 'h' | 'd';
const UNIT_MIN: Record<Unit, number> = { min: 1, h: 60, d: 1440 };
const UNIT_LABEL: Record<Unit, string> = { min: 'minutes', h: 'hours', d: 'days' };

const split = (minutes: number): { n: string; unit: Unit } =>
  minutes % 1440 === 0 ? { n: String(minutes / 1440), unit: 'd' } : minutes % 60 === 0 ? { n: String(minutes / 60), unit: 'h' } : { n: String(minutes), unit: 'min' };

const toMinutes = (n: string, unit: Unit) => (n.trim() === '' || !/^\d+$/.test(n.trim()) ? NaN : Number(n.trim()) * UNIT_MIN[unit]);

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  policy?: SlaPolicyView | null;
  onSaved: (p: SlaPolicyView, mode: 'created' | 'updated') => void;
}

type Errors = Partial<Record<'serviceId' | 'priority' | 'responseMinutes' | 'resolutionMinutes', string>>;

function DurationField({ id, label, n, unit, error, onN, onUnit }: { id: string; label: string; n: string; unit: Unit; error?: string; onN: (v: string) => void; onUnit: (u: Unit) => void }) {
  return (
    <FormField id={id} label={label} error={error}>
      <div className="flex gap-2">
        <Input id={id} inputMode="numeric" value={n} aria-invalid={!!error} aria-describedby={error ? `${id}-error` : undefined} onChange={(e) => onN(e.target.value)} className="min-w-0 flex-1" />
        <Select value={unit} onValueChange={(v) => onUnit(v as Unit)}>
          <SelectTrigger aria-label={`${label} unit`} className="w-[110px]"><SelectValue /></SelectTrigger>
          <SelectContent>{(Object.keys(UNIT_LABEL) as Unit[]).map((u) => <SelectItem key={u} value={u}>{UNIT_LABEL[u]}</SelectItem>)}</SelectContent>
        </Select>
      </div>
    </FormField>
  );
}

export function SlaFormDialog({ open, onOpenChange, policy, onSaved }: Props) {
  const editing = !!policy;
  const [serviceId, setServiceId] = useState('');
  const [priority, setPriority] = useState<Priority | ''>('');
  const [resp, setResp] = useState({ n: '', unit: 'min' as Unit });
  const [reso, setReso] = useState({ n: '', unit: 'h' as Unit });
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const services = useResource(() => (open && !editing ? itServiceService.options() : Promise.resolve([])), [open, editing]);

  useEffect(() => {
    if (!open) return;
    setServiceId(policy?.serviceId ?? '');
    setPriority(policy?.priority ?? '');
    setResp(policy ? split(policy.responseMinutes) : { n: '', unit: 'min' });
    setReso(policy ? split(policy.resolutionMinutes) : { n: '', unit: 'h' });
    setErrors({});
    setFormError(null);
  }, [open, policy]);

  const responseMinutes = toMinutes(resp.n, resp.unit);
  const resolutionMinutes = toMinutes(reso.n, reso.unit);
  const serviceName = editing ? policy!.serviceName : services.data?.find((s) => s.id === serviceId)?.name;
  const summary = priority && serviceName && Number.isFinite(responseMinutes) && Number.isFinite(resolutionMinutes) && responseMinutes > 0 && resolutionMinutes > 0
    ? `${priority} incidents on ${serviceName}: respond within ${formatMinutes(responseMinutes)}, resolve within ${formatMinutes(resolutionMinutes)}.` : null;

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const input = { serviceId, priority, responseMinutes, resolutionMinutes };
    const e = validateSlaInput(input);
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setFormError(null);
    try {
      const saved = editing ? await slaService.update(policy!.id, { responseMinutes, resolutionMinutes }) : await slaService.create(input);
      onSaved(saved, editing ? 'updated' : 'created');
      onOpenChange(false);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not save the policy.');
    } finally {
      setBusy(false);
    }
  };

  const activeServices = (services.data ?? []).filter((s) => s.status === 'ACTIVE');

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{editing ? 'Edit SLA policy' : 'Add SLA policy'}</DialogTitle>
          <DialogDescription>Targets run from the moment an incident is reported.</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-4">
          {formError && <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm font-medium text-destructive">{formError}</p>}

          {editing ? (
            <dl className="grid grid-cols-2 gap-3 rounded-md border bg-muted/50 p-3 text-sm">
              <div><dt className="text-xs text-muted-foreground">Service</dt><dd className="font-medium">{policy!.serviceName}</dd></div>
              <div><dt className="text-xs text-muted-foreground">Priority</dt><dd className="font-medium">{PRIORITY_LABEL[policy!.priority]}</dd></div>
            </dl>
          ) : (
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField id="sla-service" label="Service" error={errors.serviceId}>
                <Select value={serviceId} onValueChange={(v) => { setServiceId(v); setErrors((p) => ({ ...p, serviceId: undefined })); }}>
                  <SelectTrigger id="sla-service" aria-invalid={!!errors.serviceId}><SelectValue placeholder={services.loading ? 'Loading…' : 'Choose…'} /></SelectTrigger>
                  <SelectContent>{activeServices.map((s) => <SelectItem key={s.id} value={s.id}>{s.name}</SelectItem>)}</SelectContent>
                </Select>
              </FormField>
              <FormField id="sla-priority" label="Priority" error={errors.priority}>
                <Select value={priority} onValueChange={(v) => { setPriority(v as Priority); setErrors((p) => ({ ...p, priority: undefined })); }}>
                  <SelectTrigger id="sla-priority" aria-invalid={!!errors.priority}><SelectValue placeholder="Choose…" /></SelectTrigger>
                  <SelectContent>{PRIORITIES.map((p) => <SelectItem key={p} value={p}>{PRIORITY_LABEL[p]}</SelectItem>)}</SelectContent>
                </Select>
              </FormField>
            </div>
          )}

          <DurationField id="sla-response" label="Response target" n={resp.n} unit={resp.unit} error={errors.responseMinutes}
            onN={(v) => { setResp((p) => ({ ...p, n: v })); setErrors((p) => ({ ...p, responseMinutes: undefined })); }} onUnit={(u) => setResp((p) => ({ ...p, unit: u }))} />
          <DurationField id="sla-resolution" label="Resolution target" n={reso.n} unit={reso.unit} error={errors.resolutionMinutes}
            onN={(v) => { setReso((p) => ({ ...p, n: v })); setErrors((p) => ({ ...p, resolutionMinutes: undefined })); }} onUnit={(u) => setReso((p) => ({ ...p, unit: u }))} />

          {summary && <p className="rounded-md bg-muted p-3 text-sm" data-testid="sla-summary">{summary}</p>}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
            <Button type="submit" disabled={busy}>{busy && <Loader2 className="animate-spin" aria-hidden="true" />}{editing ? 'Save changes' : 'Add policy'}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
