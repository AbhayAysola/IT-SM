'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { AlertTriangle, Loader2, Plus, Shuffle, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { ConfirmDialog } from '../components/confirm-dialog';
import { FormError, withServerFields } from '../components/form-error';
import { FormField } from '../components/form-field';
import { PageHeader } from '../components/page-header';
import { EmptyState, ErrorState } from '../components/states';
import { formatDateTime } from '../components/status-badges';
import { useToast } from '../components/toast';
import { useResource } from '../hooks/use-resource';
import { assignmentApi, validateAssignment, type AssignmentForm } from '../services/assignment-api';
import { categoryApi } from '../services/category-api';
import { ServiceError, type AssignmentSettings, type StrategyCatalogStatus } from '../types';

const asError = (e: unknown) => (e instanceof ServiceError ? e : new ServiceError('UNAVAILABLE', 'Could not save the settings.'));
const toForm = (s: AssignmentSettings): AssignmentForm => ({
  activeStrategy: s.activeStrategy, defaultMaxWorkload: String(s.defaultMaxWorkload),
  overrides: s.categoryOverrides.map((o) => ({ categoryId: o.categoryId, strategy: o.strategy })),
});
const sameForm = (a: AssignmentForm, b: AssignmentForm) =>
  a.activeStrategy === b.activeStrategy && a.defaultMaxWorkload.trim() === b.defaultMaxWorkload.trim() &&
  a.overrides.length === b.overrides.length && a.overrides.every((o, i) => o.categoryId === b.overrides[i].categoryId && o.strategy === b.overrides[i].strategy);

/** What the banner says for each catalog state. ABSENT and FAILED are different problems and must not read alike. */
const CATALOG_NOTICE: Record<'ABSENT' | 'FAILED' | 'EMPTY', { title: string; body: string }> = {
  ABSENT: {
    title: 'The assignment strategy catalog has not been deployed yet.',
    body: 'Strategy keys cannot be verified, so changes are refused until it is deployed. The current configuration is shown read-only.',
  },
  FAILED: {
    title: 'The assignment strategy catalog could not be reached.',
    body: 'This is a temporary problem, not a missing feature. Strategy keys cannot be verified right now, so the configuration is read-only. Try again in a moment.',
  },
  EMPTY: {
    title: 'No assignment strategies are currently registered.',
    body: 'The catalog answered but lists none, so there is nothing valid to choose and the configuration is read-only. Strategies appear here once the routing engine registers them.',
  },
};

export default function AssignmentSettingsPage() {
  const toast = useToast();
  const settings = useResource(() => assignmentApi.get(), []);
  const categories = useResource(() => categoryApi.list({ sort: 'name', size: 100 }), []);
  const saved = settings.data;

  const [form, setForm] = useState<AssignmentForm | null>(null);
  const [version, setVersion] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ServiceError | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState(false);

  // First load (and an explicit reload) fills the form; saving replaces it with the server's answer.
  useEffect(() => { if (saved) { setForm(toForm(saved)); setVersion(saved.version); } }, [saved]);

  // The status is the source of truth. Only an AVAILABLE catalog with at least one key makes the form editable.
  const status: StrategyCatalogStatus | null = saved?.strategyCatalogStatus ?? null;
  const registered = saved?.availableStrategies ?? [];
  const notice = status === 'ABSENT' ? 'ABSENT' : status === 'FAILED' ? 'FAILED' : status === 'AVAILABLE' && registered.length === 0 ? 'EMPTY' : null;
  const readOnly = notice !== null;
  // Selectable options are the registered keys, and nothing else. A saved key that is not among them is listed as a
  // disabled entry so its current value stays visible (and is never silently replaced), but it cannot be picked.
  const saveable = (key: string) => status === 'AVAILABLE' && registered.includes(key);
  const labelFor = (key: string) => (status === 'AVAILABLE' ? (registered.includes(key) ? key : `${key} (not registered)`) : `${key} (unverified)`);
  const optionsFor = (current: string) => (
    <>
      {registered.map((k) => <SelectItem key={k} value={k}>{k}</SelectItem>)}
      {!registered.includes(current) && <SelectItem value={current} disabled>{labelFor(current)}</SelectItem>}
    </>
  );
  const categoryName = useMemo(() => {
    const m = new Map<number, string>();
    (categories.data?.items ?? []).forEach((c) => m.set(c.id, c.name + (c.active ? '' : ' (inactive)')));
    saved?.categoryOverrides.forEach((o) => { if (!m.has(o.categoryId)) m.set(o.categoryId, o.categoryName + (o.categoryActive ? '' : ' (inactive)')); });
    return m;
  }, [categories.data, saved]);

  if (settings.error && !saved) return (<><PageHeader title="Assignment settings" /><Card><ErrorState message={settings.error} onRetry={settings.reload} /></Card></>);
  if (!saved || !form) return (
    <>
      <PageHeader title="Assignment settings" />
      <div role="status" aria-label="Loading assignment settings" className="space-y-6"><Skeleton className="h-40" /><Skeleton className="h-72" /></div>
    </>
  );

  const dirty = !sameForm(form, toForm(saved));
  const set = (patch: Partial<AssignmentForm>) => { setForm({ ...form, ...patch }); setErrors({}); };
  const usedCategories = new Set(form.overrides.map((o) => o.categoryId));
  const freeCategories = (categories.data?.items ?? []).filter((c) => c.active && !usedCategories.has(c.id));

  const save = async () => {
    const local = validateAssignment(form, registered);
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true); setFailure(null);
    try {
      const s = await assignmentApi.update(form, version);
      toast.success('Assignment settings were saved.');
      setForm(toForm(s)); setVersion(s.version);
      settings.reload();
    } catch (e) {
      const err = asError(e); setFailure(err); setErrors(withServerFields(local, err));
    } finally { setBusy(false); }
  };
  const keepMine = async () => {
    setBusy(true);
    try { setVersion((await assignmentApi.get()).version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };
  const loadLatest = async () => {
    setBusy(true);
    try { const s = await assignmentApi.get(); setForm(toForm(s)); setVersion(s.version); setFailure(null); settings.reload(); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };

  const disabled = busy || readOnly;

  return (
    <>
      <PageHeader title="Assignment settings"
        description="Which strategy routes new incidents to technicians, the default workload ceiling, and per-category strategy overrides. This records configuration only; the routing engine applies it." />
      <div className="space-y-6">
        {notice && (
          <div role="status" data-testid="catalog-unavailable" data-catalog-status={status} data-catalog-notice={notice}
            className="flex items-start gap-3 rounded-md border border-warning/40 bg-warning/10 p-4 text-sm">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-warning" aria-hidden="true" />
            <div className="flex-1 space-y-2">
              <p><strong>{CATALOG_NOTICE[notice].title}</strong> {CATALOG_NOTICE[notice].body}</p>
              {notice === 'FAILED' && <Button size="sm" variant="outline" onClick={settings.reload}>Try again</Button>}
            </div>
          </div>
        )}
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Current configuration</CardTitle>
            <CardDescription>Last changed {formatDateTime(saved.updatedAt)}{saved.updatedByUserId !== null ? ` by user #${saved.updatedByUserId}` : ' (default configuration)'}. Saving replaces the whole configuration.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            <FormError error={failure} onKeepMine={keepMine} onLoadLatest={loadLatest} busy={busy} />
            <div className="grid gap-4 sm:grid-cols-2">
              <FormField id="as-strategy" label="Active strategy" error={errors.activeStrategy} hint={status === 'AVAILABLE' && !saveable(form.activeStrategy) ? 'The saved strategy is no longer registered. Choose a registered one before saving.' : 'Strategies are registered by the routing engine; only those can be chosen.'}>
                <Select value={form.activeStrategy} disabled={disabled} onValueChange={(v) => set({ activeStrategy: v })}>
                  <SelectTrigger id="as-strategy" aria-invalid={!!errors.activeStrategy}><SelectValue /></SelectTrigger>
                  <SelectContent>{optionsFor(form.activeStrategy)}</SelectContent>
                </Select>
              </FormField>
              <FormField id="as-workload" label="Default maximum workload" error={errors.defaultMaxWorkload} hint="Whole number 1–100. A configured ceiling, not live workload.">
                <Input id="as-workload" inputMode="numeric" value={form.defaultMaxWorkload} disabled={disabled} aria-invalid={!!errors.defaultMaxWorkload}
                  aria-describedby={errors.defaultMaxWorkload ? 'as-workload-error' : 'as-workload-hint'} onChange={(e) => set({ defaultMaxWorkload: e.target.value })} />
              </FormField>
            </div>

            <fieldset className="space-y-2">
              <legend className="text-sm font-medium">Category overrides</legend>
              <p className="text-xs text-muted-foreground">Categories without an override use the active strategy. This list is the complete set of overrides.</p>
              {categories.error && <p role="alert" className="text-sm text-destructive">{categories.error}</p>}
              {form.overrides.length === 0 && <EmptyState icon={Shuffle} title="No overrides" description="Every category uses the active strategy." />}
              {form.overrides.map((o, i) => {
                const err = errors[`categoryOverrides[${i}].strategy`] ?? errors[`categoryOverrides[${i}]`];
                return (
                  <div key={o.categoryId} className="flex flex-wrap items-start gap-2" data-testid="override-row">
                    <p className="min-w-[10rem] flex-1 self-center text-sm font-medium">{categoryName.get(o.categoryId) ?? `Category ${o.categoryId}`}</p>
                    <div className="w-56">
                      <Select value={o.strategy} disabled={disabled} onValueChange={(v) => set({ overrides: form.overrides.map((x, j) => (j === i ? { ...x, strategy: v } : x)) })}>
                        <SelectTrigger aria-label={`Strategy for ${categoryName.get(o.categoryId) ?? o.categoryId}`} aria-invalid={!!err}><SelectValue /></SelectTrigger>
                        <SelectContent>{optionsFor(o.strategy)}</SelectContent>
                      </Select>
                      {err && <p role="alert" className="mt-1 text-sm font-medium text-destructive">{err}</p>}
                    </div>
                    <Button type="button" variant="ghost" size="icon" disabled={disabled} aria-label={`Remove override for ${categoryName.get(o.categoryId) ?? o.categoryId}`}
                      onClick={() => set({ overrides: form.overrides.filter((_, j) => j !== i) })}><Trash2 aria-hidden="true" /></Button>
                  </div>
                );
              })}
              {errors.categoryOverrides && <p role="alert" className="text-sm font-medium text-destructive">{errors.categoryOverrides}</p>}
              <Button type="button" variant="outline" size="sm" disabled={disabled || freeCategories.length === 0 || registered.length === 0}
                onClick={() => set({ overrides: [...form.overrides, { categoryId: freeCategories[0].id, strategy: registered.includes(form.activeStrategy) ? form.activeStrategy : registered[0] }] })}>
                <Plus aria-hidden="true" /> Add override
              </Button>
            </fieldset>

            <div className="flex flex-wrap justify-end gap-2 border-t pt-4">
              <Button variant="outline" disabled={disabled || !dirty} onClick={() => { setForm(toForm(saved)); setErrors({}); setFailure(null); }}>Discard changes</Button>
              <Button disabled={disabled || !dirty} onClick={() => { const local = validateAssignment(form, registered); setErrors(local); if (!Object.keys(local).length) setConfirm(true); }}>
                {busy && <Loader2 className="animate-spin" aria-hidden="true" />} Save settings
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
      <ConfirmDialog open={confirm} onOpenChange={setConfirm} title="Save assignment settings?"
        description="This replaces the whole configuration and changes how new incidents are routed once the routing engine reads it."
        confirmLabel="Save settings" onConfirm={async () => { await save(); }} />
    </>
  );
}
