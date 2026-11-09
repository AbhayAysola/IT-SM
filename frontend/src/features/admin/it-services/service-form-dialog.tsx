'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FormField } from '../components/form-field';
import { Skeleton } from '@/components/ui/skeleton';
import { useResource } from '../hooks/use-resource';
import { itServiceService, validateServiceInput, wouldCreateCycle, type ServiceInput } from '../services/service-service';
import { CRITICALITIES, CRITICALITY_LABEL, TEAMS, type Criticality, type ServiceView } from '../types';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  service?: ServiceView | null;
  onSaved: (s: ServiceView, mode: 'created' | 'updated') => void;
}

const EMPTY: ServiceInput = { name: '', description: '', ownerTeam: '', criticality: 'MEDIUM', dependsOn: [] };
type Errors = Partial<Record<keyof ServiceInput, string>>;

export function ServiceFormDialog({ open, onOpenChange, service, onSaved }: Props) {
  const editing = !!service;
  const [values, setValues] = useState<ServiceInput>(EMPTY);
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const options = useResource(() => (open ? itServiceService.options() : Promise.resolve([])), [open]);

  useEffect(() => {
    if (!open) return;
    setValues(service ? { name: service.name, description: service.description, ownerTeam: service.ownerTeam, criticality: service.criticality, dependsOn: service.dependsOn } : EMPTY);
    setErrors({});
    setFormError(null);
  }, [open, service]);

  const set = <K extends keyof ServiceInput>(k: K, v: ServiceInput[K]) => {
    setValues((p) => ({ ...p, [k]: v }));
    setErrors((p) => ({ ...p, [k]: undefined }));
  };
  const all = useMemo(() => options.data ?? [], [options.data]);

  // For each candidate: why it cannot be chosen (or null if it can).
  const blockedReason = useMemo(() => {
    const out = new Map<string, string>();
    for (const s of all) {
      if (service && s.id === service.id) continue; // excluded from the list entirely
      const checked = values.dependsOn.includes(s.id);
      if (s.status === 'INACTIVE' && !checked) out.set(s.id, 'Inactive');
      else if (!checked && service && wouldCreateCycle(all, service.id, s.id)) out.set(s.id, 'Would create a cycle');
    }
    return out;
  }, [all, service, values.dependsOn]);

  const toggleDep = (id: string) => {
    set('dependsOn', values.dependsOn.includes(id) ? values.dependsOn.filter((x) => x !== id) : [...values.dependsOn, id]);
    setErrors((p) => ({ ...p, dependsOn: undefined }));
  };

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const e = validateServiceInput(values);
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setFormError(null);
    try {
      const saved = editing ? await itServiceService.update(service!.id, values) : await itServiceService.create(values);
      onSaved(saved, editing ? 'updated' : 'created');
      onOpenChange(false);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not save the service.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{editing ? 'Edit service' : 'Add service'}</DialogTitle>
          <DialogDescription>Criticality and dependencies show who is affected when this service has a problem.</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-4">
          {formError && <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm font-medium text-destructive">{formError}</p>}
          <FormField id="svc-name" label="Name" error={errors.name}>
            <Input id="svc-name" value={values.name} aria-invalid={!!errors.name} aria-describedby={errors.name ? 'svc-name-error' : undefined}
              onChange={(e) => set('name', e.target.value)} />
          </FormField>
          <FormField id="svc-description" label="Description" error={errors.description}>
            <textarea id="svc-description" rows={3} value={values.description} aria-invalid={!!errors.description}
              aria-describedby={errors.description ? 'svc-description-error' : undefined}
              onChange={(e) => set('description', e.target.value)}
              className="flex w-full rounded-md border border-input bg-transparent px-3 py-2 text-base shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background aria-[invalid=true]:border-destructive md:text-sm" />
          </FormField>
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField id="svc-team" label="Owner team" error={errors.ownerTeam}>
              <Select value={values.ownerTeam} onValueChange={(v) => { set('ownerTeam', v); setErrors((p) => ({ ...p, ownerTeam: undefined })); }}>
                <SelectTrigger id="svc-team" aria-invalid={!!errors.ownerTeam}><SelectValue placeholder="Choose…" /></SelectTrigger>
                <SelectContent>{TEAMS.map((t) => <SelectItem key={t} value={t}>{t}</SelectItem>)}</SelectContent>
              </Select>
            </FormField>
            <FormField id="svc-criticality" label="Business criticality">
              <Select value={values.criticality} onValueChange={(v) => set('criticality', v as Criticality)}>
                <SelectTrigger id="svc-criticality"><SelectValue /></SelectTrigger>
                <SelectContent>{CRITICALITIES.map((c) => <SelectItem key={c} value={c}>{CRITICALITY_LABEL[c]}</SelectItem>)}</SelectContent>
              </Select>
            </FormField>
          </div>

          <fieldset className="space-y-1.5">
            <legend className="text-sm font-medium leading-none">Depends on</legend>
            <p className="text-xs text-muted-foreground">Services this one needs in order to work. Circular and inactive choices are unavailable.</p>
            {options.loading ? <Skeleton className="h-24 w-full" /> : (
              <ul className="max-h-48 divide-y overflow-y-auto rounded-md border" data-testid="dependency-options">
                {all.filter((s) => !service || s.id !== service.id).map((s) => {
                  const reason = blockedReason.get(s.id);
                  const id = `dep-${s.id}`;
                  return (
                    <li key={s.id} className="flex items-center gap-3 px-3 py-2">
                      <input id={id} type="checkbox" className="h-4 w-4 accent-primary" checked={values.dependsOn.includes(s.id)} disabled={!!reason} onChange={() => toggleDep(s.id)} />
                      <label htmlFor={id} className="flex-1 text-sm">{s.name}</label>
                      {reason && <span className="text-xs text-muted-foreground">{reason}</span>}
                    </li>
                  );
                })}
              </ul>
            )}
            {errors.dependsOn && <p role="alert" className="text-sm font-medium text-destructive">{errors.dependsOn}</p>}
          </fieldset>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
            <Button type="submit" disabled={busy}>{busy && <Loader2 className="animate-spin" aria-hidden="true" />}{editing ? 'Save changes' : 'Add service'}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
