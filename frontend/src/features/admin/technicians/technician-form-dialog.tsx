'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { cn } from '@/lib/utils';
import { FormField } from '../components/form-field';
import { AVAILABILITY_LABEL, SKILL_LEVEL_LABEL } from '../components/status-badges';
import { useResource } from '../hooks/use-resource';
import { technicianService, validateTechnicianInput, type TechnicianInput } from '../services/technician-service';
import { SHIFTS, SKILL_OPTIONS, TEAMS, type Availability, type SkillLevel, type TechnicianView } from '../types';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  /** Edit mode when present, otherwise "add profile" mode. */
  technician?: TechnicianView | null;
  onSaved: (t: TechnicianView, mode: 'created' | 'updated') => void;
}

const DEFAULTS: TechnicianInput = {
  skills: [], skillLevel: 'JUNIOR', availability: 'AVAILABLE', maxWorkload: 5, isTeamLead: false, team: 'Service Desk', shift: SHIFTS[0],
};

type Errors = Partial<Record<keyof TechnicianInput | 'account', string>>;

export function TechnicianFormDialog({ open, onOpenChange, technician, onSaved }: Props) {
  const editing = !!technician;
  const [values, setValues] = useState<TechnicianInput>(DEFAULTS);
  const [maxText, setMaxText] = useState('5');
  const [userId, setUserId] = useState('');
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const candidates = useResource(() => (open && !editing ? technicianService.unprofiled() : Promise.resolve([])), [open, editing]);

  useEffect(() => {
    if (!open) return;
    const v: TechnicianInput = technician
      ? { skills: technician.skills, skillLevel: technician.skillLevel, availability: technician.availability, maxWorkload: technician.maxWorkload, isTeamLead: technician.isTeamLead, team: technician.team, shift: technician.shift }
      : DEFAULTS;
    setValues(v);
    setMaxText(String(v.maxWorkload));
    setUserId('');
    setErrors({});
    setFormError(null);
  }, [open, technician]);

  const set = <K extends keyof TechnicianInput>(k: K, v: TechnicianInput[K]) => {
    setValues((p) => ({ ...p, [k]: v }));
    setErrors((p) => ({ ...p, [k]: undefined }));
  };
  const toggleSkill = (s: string) => {
    set('skills', values.skills.includes(s) ? values.skills.filter((x) => x !== s) : [...values.skills, s]);
    setErrors((p) => ({ ...p, skills: undefined }));
  };
  const onMax = (t: string) => {
    setMaxText(t);
    set('maxWorkload', t.trim() === '' ? NaN : Number(t));
  };

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const e: Errors = validateTechnicianInput(values);
    if (!editing && !userId) e.account = 'Choose the technician account this profile belongs to.';
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setFormError(null);
    try {
      const saved = editing ? await technicianService.update(technician!.id, values) : await technicianService.create(userId, values);
      onSaved(saved, editing ? 'updated' : 'created');
      onOpenChange(false);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not save the profile.');
    } finally {
      setBusy(false);
    }
  };

  const noCandidates = !editing && !candidates.loading && !candidates.error && (candidates.data?.length ?? 0) === 0;

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{editing ? `Edit ${technician!.name}` : 'Add technician profile'}</DialogTitle>
          <DialogDescription>Skills and workload limits decide which incidents this technician can be routed.</DialogDescription>
        </DialogHeader>

        {noCandidates ? (
          <div className="space-y-3 py-2 text-sm">
            <p className="text-foreground">Every technician account already has a profile.</p>
            <p className="text-muted-foreground">
              To add someone new, <Link href="/admin/users" className="font-medium text-primary underline underline-offset-4">add a user with the Technician role</Link>;
              a profile is created for them automatically.
            </p>
            <DialogFooter><Button variant="outline" onClick={() => onOpenChange(false)}>Close</Button></DialogFooter>
          </div>
        ) : (
          <form onSubmit={submit} noValidate className="space-y-4">
            {formError && (
              <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm font-medium text-destructive">{formError}</p>
            )}

            {!editing && (
              <FormField id="tech-account" label="Technician account" error={errors.account}>
                <Select value={userId} onValueChange={(v) => { setUserId(v); setErrors((p) => ({ ...p, account: undefined })); }}>
                  <SelectTrigger id="tech-account" aria-invalid={!!errors.account}><SelectValue placeholder={candidates.loading ? 'Loading…' : 'Choose…'} /></SelectTrigger>
                  <SelectContent>
                    {(candidates.data ?? []).map((u) => <SelectItem key={u.id} value={u.id}>{u.name} ({u.email})</SelectItem>)}
                  </SelectContent>
                </Select>
              </FormField>
            )}

            <fieldset className="space-y-1.5">
              <legend className="text-sm font-medium leading-none">Skills</legend>
              <div className="flex flex-wrap gap-2 pt-1" role="group" aria-label="Skills">
                {SKILL_OPTIONS.map((s) => {
                  const on = values.skills.includes(s);
                  return (
                    <button
                      key={s} type="button" aria-pressed={on} onClick={() => toggleSkill(s)}
                      className={cn(
                        'rounded-md border px-2.5 py-1 text-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                        on ? 'border-primary bg-primary/10 font-medium text-primary' : 'border-input bg-background text-foreground hover:bg-accent'
                      )}
                    >
                      {on && <span aria-hidden="true">✓ </span>}{s}
                    </button>
                  );
                })}
              </div>
              {errors.skills && <p role="alert" className="text-sm font-medium text-destructive">{errors.skills}</p>}
            </fieldset>

            <div className="grid gap-4 sm:grid-cols-2">
              <FormField id="tech-level" label="Skill level">
                <Select value={values.skillLevel} onValueChange={(v) => set('skillLevel', v as SkillLevel)}>
                  <SelectTrigger id="tech-level"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {(Object.keys(SKILL_LEVEL_LABEL) as SkillLevel[]).map((l) => <SelectItem key={l} value={l}>{SKILL_LEVEL_LABEL[l]}</SelectItem>)}
                  </SelectContent>
                </Select>
              </FormField>
              <FormField id="tech-availability" label="Availability">
                <Select value={values.availability} onValueChange={(v) => set('availability', v as Availability)}>
                  <SelectTrigger id="tech-availability"><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {(Object.keys(AVAILABILITY_LABEL) as Availability[]).map((a) => <SelectItem key={a} value={a}>{AVAILABILITY_LABEL[a]}</SelectItem>)}
                  </SelectContent>
                </Select>
              </FormField>
              <FormField id="tech-team" label="Team" error={errors.team}>
                <Select value={values.team} onValueChange={(v) => set('team', v)}>
                  <SelectTrigger id="tech-team"><SelectValue /></SelectTrigger>
                  <SelectContent>{TEAMS.map((t) => <SelectItem key={t} value={t}>{t}</SelectItem>)}</SelectContent>
                </Select>
              </FormField>
              <FormField id="tech-shift" label="Shift" error={errors.shift}>
                <Select value={values.shift} onValueChange={(v) => set('shift', v)}>
                  <SelectTrigger id="tech-shift"><SelectValue /></SelectTrigger>
                  <SelectContent>{SHIFTS.map((t) => <SelectItem key={t} value={t}>{t}</SelectItem>)}</SelectContent>
                </Select>
              </FormField>
              <FormField id="tech-max" label="Maximum workload" error={errors.maxWorkload} hint={editing ? `Currently handling ${technician!.currentWorkload}.` : 'Incidents held at once.'}>
                <Input
                  id="tech-max" inputMode="numeric" value={maxText} aria-invalid={!!errors.maxWorkload}
                  aria-describedby={errors.maxWorkload ? 'tech-max-error' : 'tech-max-hint'}
                  onChange={(e) => onMax(e.target.value)}
                />
              </FormField>
              <div className="flex items-end pb-2">
                <div className="flex items-center gap-2">
                  <input id="tech-lead" type="checkbox" checked={values.isTeamLead} onChange={(e) => set('isTeamLead', e.target.checked)} className="h-4 w-4 accent-primary" />
                  <Label htmlFor="tech-lead">Team lead</Label>
                </div>
              </div>
            </div>

            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
              <Button type="submit" disabled={busy}>
                {busy && <Loader2 className="animate-spin" aria-hidden="true" />}
                {editing ? 'Save changes' : 'Add profile'}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
