'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FormError, withServerFields } from '../components/form-error';
import { FormField } from '../components/form-field';
import { useResource } from '../hooks/use-resource';
import { itServiceApi, validateService, type ServiceForm } from '../services/it-service-api';
import { CRITICALITY_LABEL, CRITICALITY_LEVELS, ServiceError, type ItService } from '../types';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  service?: ItService | null;
  onSaved: (s: ItService, mode: 'created' | 'updated') => void;
}

const EMPTY: ServiceForm = { name: '', description: '', businessCriticality: 3, ownerTeam: '', dependsOnServiceIds: [] };
const asError = (e: unknown) => (e instanceof ServiceError ? e : new ServiceError('UNAVAILABLE', 'Could not save the service.'));
const toForm = (s: ItService): ServiceForm => ({
  name: s.name, description: s.description ?? '', businessCriticality: s.businessCriticality, ownerTeam: s.ownerTeam ?? '', dependsOnServiceIds: s.dependsOn.map((d) => d.id),
});
const TEXTAREA = 'flex min-h-[76px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring aria-[invalid=true]:border-destructive';

export function ServiceFormDialog({ open, onOpenChange, service, onSaved }: Props) {
  const editing = !!service;
  const [v, setV] = useState<ServiceForm>(EMPTY);
  const [version, setVersion] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ServiceError | null>(null);
  const [busy, setBusy] = useState(false);

  const all = useResource(() => (open ? itServiceApi.list({ sort: 'name', size: 100 }) : Promise.resolve(null)), [open]);
  const options = useMemo(() => {
    const byId = new Map<number, { id: number; name: string; active: boolean }>();
    (all.data?.items ?? []).forEach((s) => byId.set(s.id, s));
    service?.dependsOn.forEach((d) => { if (!byId.has(d.id)) byId.set(d.id, d); });
    return Array.from(byId.values()).filter((s) => s.id !== service?.id);
  }, [all.data, service]);

  useEffect(() => {
    if (!open) return;
    setV(service ? toForm(service) : EMPTY); setVersion(service?.version ?? 0); setErrors({}); setFailure(null);
  }, [open, service]);

  const set = <K extends keyof ServiceForm>(k: K, val: ServiceForm[K]) => { setV((p) => ({ ...p, [k]: val })); setErrors((p) => ({ ...p, [k]: '' })); };
  const toggleDep = (id: number) => set('dependsOnServiceIds', v.dependsOnServiceIds.includes(id) ? v.dependsOnServiceIds.filter((x) => x !== id) : [...v.dependsOnServiceIds, id]);

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const local = validateService(v, service?.id);
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true); setFailure(null);
    try {
      const saved = editing ? await itServiceApi.update(service!.id, v, version) : await itServiceApi.create(v);
      onSaved(saved, editing ? 'updated' : 'created');
      onOpenChange(false);
    } catch (e) {
      const err = asError(e); setFailure(err); setErrors(withServerFields(local, err));
    } finally { setBusy(false); }
  };
  const keepMine = async () => {
    setBusy(true);
    try { setVersion((await itServiceApi.get(service!.id)).version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };
  const loadLatest = async () => {
    setBusy(true);
    try { const s = await itServiceApi.get(service!.id); setV(toForm(s)); setVersion(s.version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{editing ? 'Edit IT service' : 'Add IT service'}</DialogTitle>
          <DialogDescription>Services are deactivated, never deleted. Dependencies must not form a loop; the server checks that when you save.</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-4">
          <FormError error={failure} onKeepMine={editing ? keepMine : undefined} onLoadLatest={editing ? loadLatest : undefined} busy={busy} />
          <FormField id="svc-name" label="Name" error={errors.name}>
            <Input id="svc-name" value={v.name} autoComplete="off" aria-invalid={!!errors.name} aria-describedby={errors.name ? 'svc-name-error' : undefined} onChange={(e) => set('name', e.target.value)} />
          </FormField>
          <FormField id="svc-desc" label="Description (optional)" error={errors.description}>
            <textarea id="svc-desc" className={TEXTAREA} value={v.description} aria-invalid={!!errors.description} aria-describedby={errors.description ? 'svc-desc-error' : undefined} onChange={(e) => set('description', e.target.value)} />
          </FormField>
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField id="svc-owner" label="Owner team (optional)" error={errors.ownerTeam}>
              <Input id="svc-owner" value={v.ownerTeam} autoComplete="off" aria-invalid={!!errors.ownerTeam} aria-describedby={errors.ownerTeam ? 'svc-owner-error' : undefined} onChange={(e) => set('ownerTeam', e.target.value)} />
            </FormField>
            <FormField id="svc-crit" label="Business criticality" error={errors.businessCriticality}>
              <Select value={String(v.businessCriticality)} onValueChange={(x) => set('businessCriticality', Number(x))}>
                <SelectTrigger id="svc-crit"><SelectValue /></SelectTrigger>
                <SelectContent>{CRITICALITY_LEVELS.map((n) => <SelectItem key={n} value={String(n)}>{CRITICALITY_LABEL[n]}</SelectItem>)}</SelectContent>
              </Select>
            </FormField>
          </div>
          <fieldset className="space-y-2">
            <legend className="text-sm font-medium">Depends on</legend>
            <p className="text-xs text-muted-foreground">Services this one needs in order to work.</p>
            {all.error && <p role="alert" className="text-sm text-destructive">{all.error}</p>}
            <div className="max-h-44 space-y-1 overflow-y-auto rounded-md border p-2" data-testid="dependency-options">
              {all.loading && !all.data ? <p className="text-sm text-muted-foreground">Loading services…</p> : options.length === 0 ? <p className="text-sm text-muted-foreground">No other services yet.</p> : options.map((s) => {
                const id = `dep-${s.id}`;
                return (
                  <label key={s.id} htmlFor={id} className="flex items-center gap-2 text-sm">
                    <input id={id} type="checkbox" className="h-4 w-4 accent-primary" checked={v.dependsOnServiceIds.includes(s.id)} onChange={() => toggleDep(s.id)} />
                    {s.name}{!s.active && <span className="text-xs text-muted-foreground">(inactive)</span>}
                  </label>
                );
              })}
            </div>
            {all.data && all.data.totalItems > 100 && <p className="text-xs text-muted-foreground">Showing the first 100 of {all.data.totalItems} services.</p>}
            {errors.dependsOnServiceIds && <p role="alert" className="text-sm font-medium text-destructive">{errors.dependsOnServiceIds}</p>}
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
