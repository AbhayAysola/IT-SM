'use client';

import React, { useEffect, useMemo, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FormError, withServerFields } from '../components/form-error';
import { FormField } from '../components/form-field';
import { useDebounced, useResource } from '../hooks/use-resource';
import { categoryApi } from '../services/category-api';
import { technicianApi, validateSkills, validateTechnician, type SkillInput, type TechnicianForm } from '../services/technician-api';
import { userApi } from '../services/user-api';
import { ServiceError, type AdminUser, type Technician } from '../types';
import { SkillsEditor } from './skills-editor';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  /** Edit mode when present, otherwise create a profile for an existing TECHNICIAN user. */
  technician?: Technician | null;
  onSaved: (t: Technician, mode: 'created' | 'updated') => void;
}

const DEFAULTS: TechnicianForm = { userId: '', maxWorkload: '5', experienceYears: '0', available: true, isTeamLead: false, reportsToUserId: '' };
const NONE = 'NONE';
const asError = (e: unknown) => (e instanceof ServiceError ? e : new ServiceError('UNAVAILABLE', 'Could not save the profile.'));
const toForm = (t: Technician): TechnicianForm => ({
  userId: String(t.userId), maxWorkload: String(t.maxWorkload), experienceYears: String(t.experienceYears), available: t.available,
  isTeamLead: t.isTeamLead, reportsToUserId: t.reportsToUserId === null ? '' : String(t.reportsToUserId),
});

export function TechnicianFormDialog({ open, onOpenChange, technician, onSaved }: Props) {
  const editing = !!technician;
  const [v, setV] = useState<TechnicianForm>(DEFAULTS);
  const [skills, setSkills] = useState<SkillInput[]>([]);
  const [version, setVersion] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ServiceError | null>(null);
  const [busy, setBusy] = useState(false);

  // Candidates: active users whose role is TECHNICIAN, found with the users list's own `search`. The API has no "without a
  // profile" filter, so existing profiles are identified from the technicians list with the SAME search term (both match
  // name or email, so they cover the same people). Anyone this still misses is refused by the server (409), whose message
  // is shown with the form kept.
  const [userSearch, setUserSearch] = useState('');
  const [chosen, setChosen] = useState<AdminUser | null>(null);
  const term = useDebounced(userSearch);
  const users = useResource(() => (open && !editing ? userApi.list({ role: 'TECHNICIAN', active: 'true', search: term, sort: 'name', size: 100 }) : Promise.resolve(null)), [open, editing, term]);
  const profiled = useResource(() => (open && !editing ? technicianApi.list({ search: term, sort: 'name', size: 100 }) : Promise.resolve(null)), [open, editing, term]);
  const hasProfile = useMemo(() => new Set((profiled.data?.items ?? []).map((t) => t.userId)), [profiled.data]);
  const candidates = useMemo(() => {
    const list = users.data?.items ?? [];
    return chosen && !list.some((u) => u.id === chosen.id) ? [chosen, ...list] : list;
  }, [users.data, chosen]);
  const truncated = (users.data?.totalItems ?? 0) > 100 || (profiled.data?.totalItems ?? 0) > 100;
  const managers = useResource(() => (open ? technicianApi.list({ active: 'true', sort: 'name', size: 100 }) : Promise.resolve(null)), [open]);
  const categories = useResource(() => (open && !editing ? categoryApi.list({ active: 'true', sort: 'name', size: 100 }) : Promise.resolve(null)), [open, editing]);
  const categoryOptions = useMemo(() => (categories.data?.items ?? []).map((c) => ({ id: c.id, label: c.name })), [categories.data]);
  const managerOptions = (managers.data?.items ?? []).filter((m) => m.userId !== technician?.userId);

  useEffect(() => {
    if (!open) return;
    setV(technician ? toForm(technician) : DEFAULTS); setSkills([]); setVersion(technician?.version ?? 0); setErrors({}); setFailure(null);
    setUserSearch(''); setChosen(null);
  }, [open, technician]);

  const set = <K extends keyof TechnicianForm>(k: K, val: TechnicianForm[K]) => { setV((p) => ({ ...p, [k]: val })); setErrors((p) => ({ ...p, [k]: '' })); };

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const local = { ...validateTechnician(v, editing ? 'edit' : 'create', technician?.userId), ...(editing ? {} : validateSkills(skills)) };
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true); setFailure(null);
    try {
      const saved = editing ? await technicianApi.update(technician!.userId, v, version) : await technicianApi.create(v, skills);
      onSaved(saved, editing ? 'updated' : 'created');
      onOpenChange(false);
    } catch (e) {
      const err = asError(e); setFailure(err); setErrors(withServerFields(local, err));
    } finally { setBusy(false); }
  };
  const keepMine = async () => {
    setBusy(true);
    try { setVersion((await technicianApi.get(technician!.userId)).version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };
  const loadLatest = async () => {
    setBusy(true);
    try { const t = await technicianApi.get(technician!.userId); setV(toForm(t)); setVersion(t.version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{editing ? `Edit ${technician!.name}` : 'Add technician profile'}</DialogTitle>
          <DialogDescription>
            {editing ? 'Skills, and whether the profile is active, are separate actions on the list.' : 'A profile can only be created for an existing user whose role is Technician. Change a user’s role under Users first.'}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-4">
          <FormError error={failure} onKeepMine={editing ? keepMine : undefined} onLoadLatest={editing ? loadLatest : undefined} busy={busy} />
          {!editing && (
            <div className="space-y-3">
              <FormField id="tech-user-search" label="Find a technician user" hint="Search by name or email. Users who already have a profile are listed but cannot be chosen.">
                <Input id="tech-user-search" type="search" value={userSearch} autoComplete="off" onChange={(e) => setUserSearch(e.target.value)} />
              </FormField>
              <FormField id="tech-user" label="Technician user" error={errors.userId}
                hint={users.error ?? profiled.error ?? (truncated ? 'More than 100 people match. Refine the search to see the rest.' : undefined)}>
                <Select value={v.userId} onValueChange={(x) => { set('userId', x); setChosen(candidates.find((u) => String(u.id) === x) ?? null); }}>
                  <SelectTrigger id="tech-user" aria-invalid={!!errors.userId}><SelectValue placeholder={users.loading ? 'Loading…' : 'Choose a user…'} /></SelectTrigger>
                  <SelectContent>
                    {candidates.length === 0 && !users.loading && <p className="px-2 py-1.5 text-sm text-muted-foreground" data-testid="no-candidates">No technician users match.</p>}
                    {candidates.map((u) => (
                      <SelectItem key={u.id} value={String(u.id)} disabled={hasProfile.has(u.id)}>
                        {u.name} · {u.email}{hasProfile.has(u.id) ? ' (already has a profile)' : ''}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </FormField>
            </div>
          )}
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField id="tech-workload" label="Maximum workload" error={errors.maxWorkload} hint="A configured ceiling (1–100), not how busy they are now.">
              <Input id="tech-workload" inputMode="numeric" value={v.maxWorkload} aria-invalid={!!errors.maxWorkload} aria-describedby={errors.maxWorkload ? 'tech-workload-error' : 'tech-workload-hint'}
                onChange={(e) => set('maxWorkload', e.target.value)} />
            </FormField>
            <FormField id="tech-experience" label="Years of experience" error={errors.experienceYears}>
              <Input id="tech-experience" inputMode="numeric" value={v.experienceYears} aria-invalid={!!errors.experienceYears} aria-describedby={errors.experienceYears ? 'tech-experience-error' : undefined}
                onChange={(e) => set('experienceYears', e.target.value)} />
            </FormField>
          </div>
          <FormField id="tech-reports" label="Reports to" error={errors.reportsToUserId} hint={managers.error ?? undefined}>
            <Select value={v.reportsToUserId === '' ? NONE : v.reportsToUserId} onValueChange={(x) => set('reportsToUserId', x === NONE ? '' : x)}>
              <SelectTrigger id="tech-reports"><SelectValue /></SelectTrigger>
              <SelectContent>
                <SelectItem value={NONE}>Nobody</SelectItem>
                {managerOptions.map((m) => <SelectItem key={m.userId} value={String(m.userId)}>{m.name}</SelectItem>)}
              </SelectContent>
            </Select>
          </FormField>
          <div className="space-y-2">
            <label className="flex items-start gap-2 text-sm">
              <input type="checkbox" className="mt-0.5 h-4 w-4 accent-primary" checked={v.available} onChange={(e) => set('available', e.target.checked)} />
              <span><span className="font-medium">Available</span><br /><span className="text-muted-foreground">An administrator’s eligibility flag (turn off for leave or off-shift). It does not guarantee a technician can be assigned right now.</span></span>
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" className="h-4 w-4 accent-primary" checked={v.isTeamLead} onChange={(e) => set('isTeamLead', e.target.checked)} />
              <span className="font-medium">Team lead</span>
            </label>
          </div>
          {!editing && (
            <>
              {categories.error && <p role="alert" className="text-sm text-destructive">{categories.error}</p>}
              <SkillsEditor skills={skills} onChange={(s) => { setSkills(s); setErrors((p) => ({ ...p, skills: '' })); }} options={categoryOptions} errors={errors} idPrefix="create-skill" />
            </>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
            <Button type="submit" disabled={busy}>{busy && <Loader2 className="animate-spin" aria-hidden="true" />}{editing ? 'Save changes' : 'Create profile'}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

export function SkillsDialog({ technician, onClose, onSaved }: { technician: Technician | null; onClose: () => void; onSaved: (t: Technician) => void }) {
  const [skills, setSkills] = useState<SkillInput[]>([]);
  const [version, setVersion] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ServiceError | null>(null);
  const [busy, setBusy] = useState(false);
  const categories = useResource(() => (technician ? categoryApi.list({ active: 'true', sort: 'name', size: 100 }) : Promise.resolve(null)), [technician]);
  const options = useMemo(() => {
    const byId = new Map<number, string>();
    (categories.data?.items ?? []).forEach((c) => byId.set(c.id, c.name));
    technician?.skills.forEach((s) => { if (!byId.has(s.categoryId)) byId.set(s.categoryId, `${s.categoryName} (inactive)`); });
    return Array.from(byId, ([id, label]) => ({ id, label }));
  }, [categories.data, technician]);

  const fromTech = (t: Technician) => t.skills.map((s) => ({ categoryId: s.categoryId, proficiency: s.proficiency }));
  useEffect(() => { if (technician) { setSkills(fromTech(technician)); setVersion(technician.version); setErrors({}); setFailure(null); } }, [technician]);

  const save = async () => {
    const local = validateSkills(skills);
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true); setFailure(null);
    try { const t = await technicianApi.updateSkills(technician!.userId, skills, version); onSaved(t); onClose(); }
    catch (e) { const err = asError(e); setFailure(err); setErrors(withServerFields(local, err)); }
    finally { setBusy(false); }
  };
  const keepMine = async () => {
    setBusy(true);
    try { setVersion((await technicianApi.get(technician!.userId)).version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };
  const loadLatest = async () => {
    setBusy(true);
    try { const t = await technicianApi.get(technician!.userId); setSkills(fromTech(t)); setVersion(t.version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };

  return (
    <Dialog open={!!technician} onOpenChange={(o) => !busy && !o && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Skills of {technician?.name}</DialogTitle>
          <DialogDescription>Saving replaces the whole skill set. Removing every row removes every skill.</DialogDescription>
        </DialogHeader>
        <FormError error={failure} onKeepMine={keepMine} onLoadLatest={loadLatest} busy={busy} />
        {categories.error && <p role="alert" className="text-sm text-destructive">{categories.error}</p>}
        <SkillsEditor skills={skills} onChange={setSkills} options={options} errors={errors} idPrefix="skill" />
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button onClick={save} disabled={busy}>{busy && <Loader2 className="animate-spin" aria-hidden="true" />} Save skills</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
