'use client';

import React, { useMemo, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { cn } from '@/lib/utils';
import { ConfirmDialog } from '../components/confirm-dialog';
import { FormField } from '../components/form-field';
import {
  assignmentService, MAX_ESCALATION, MAX_RECOMMENDATIONS, MIN_ESCALATION, POOL_LABEL, STRATEGY_INFO, TIE_BREAKER_LABEL,
  validateAssignmentSettings, type SettingsErrors,
} from '../services/assignment-service';
import { SKILL_LEVEL_LABEL } from '../components/status-badges';
import {
  ASSIGNMENT_STRATEGIES, TEAMS,
  type AssignmentSettings, type AssignmentSettingsRecord, type AssignmentStrategy, type RoundRobinPool, type SkillLevel, type TieBreaker,
} from '../types';

/** Numbers are kept as text while typing so "12a" or "" can be reported instead of silently coerced. */
interface Draft extends Omit<AssignmentSettings, 'unassignedEscalationMinutes' | 'recommendationCount' | 'skillBased'> {
  unassignedEscalationMinutes: string;
  recommendationCount: string;
  skillBased: { minSkillMatchPct: string; minSkillLevel: SkillLevel; tieBreaker: TieBreaker };
}

const num = (t: string) => (/^\d+$/.test(t.trim()) ? Number(t.trim()) : NaN);

const toDraft = (s: AssignmentSettings): Draft => ({
  ...s,
  // Teams that no longer exist can't be shown as checkboxes, so they could never be removed: drop them.
  roundRobin: { ...s.roundRobin, teams: s.roundRobin.teams.filter((t) => (TEAMS as readonly string[]).includes(t)) },
  unassignedEscalationMinutes: String(s.unassignedEscalationMinutes),
  recommendationCount: String(s.recommendationCount),
  skillBased: { ...s.skillBased, minSkillMatchPct: String(s.skillBased.minSkillMatchPct) },
});

const toSettings = (d: Draft): AssignmentSettings => ({
  ...d,
  unassignedEscalationMinutes: num(d.unassignedEscalationMinutes),
  recommendationCount: num(d.recommendationCount),
  skillBased: { ...d.skillBased, minSkillMatchPct: num(d.skillBased.minSkillMatchPct) },
});

const DEFAULT: AssignmentSettings = {
  strategy: 'SKILL_BASED', fallbackStrategy: 'MANUAL', unassignedEscalationMinutes: 30, recommendationCount: 3,
  manual: { notifyTeamLeads: true },
  roundRobin: { pool: 'ALL_TECHNICIANS', teams: [], skipAtCapacity: true },
  skillBased: { minSkillMatchPct: 60, minSkillLevel: 'JUNIOR', tieBreaker: 'LOWEST_WORKLOAD' },
};

const strip = ({ updatedAt: _a, updatedBy: _b, ...rest }: AssignmentSettingsRecord): AssignmentSettings => rest;

interface Props {
  /** null = nothing configured yet. */
  saved: AssignmentSettingsRecord | null;
  teamsWithTechnicians: string[];
  onSaved: (r: AssignmentSettingsRecord) => void;
}

export function AssignmentForm({ saved, teamsWithTechnicians, onSaved }: Props) {
  const base = useMemo(() => (saved ? strip(saved) : DEFAULT), [saved]);
  const [draft, setDraft] = useState<Draft>(() => toDraft(base));
  const [errors, setErrors] = useState<SettingsErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);

  const techTeams = useMemo(() => new Set(teamsWithTechnicians), [teamsWithTechnicians]);
  const current = toSettings(draft);
  const dirty = !saved || JSON.stringify(current) !== JSON.stringify(base);
  const switching = !!saved && current.strategy !== saved.strategy;

  const patch = (p: Partial<Draft>, clear: (keyof SettingsErrors)[] = []) => {
    setDraft((d) => ({ ...d, ...p }));
    if (clear.length) setErrors((e) => { const n = { ...e }; clear.forEach((k) => delete n[k]); return n; });
    setFormError(null);
  };

  const commit = async () => {
    setBusy(true);
    setFormError(null);
    try {
      const rec = await assignmentService.save(current);
      onSaved(rec);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : 'Could not save the settings.');
      throw e; // lets the confirm dialog show it too
    } finally {
      setBusy(false);
    }
  };

  const submit = (ev: React.FormEvent) => {
    ev.preventDefault();
    const errs = validateAssignmentSettings(current, techTeams);
    setErrors(errs);
    if (Object.keys(errs).length) return;
    if (switching) setConfirmOpen(true);
    else void commit().catch(() => undefined);
  };

  const discard = () => { setDraft(toDraft(base)); setErrors({}); setFormError(null); };
  const strategyLabel = (s: AssignmentStrategy) => STRATEGY_INFO[s].label;
  const fallbackOptions = ASSIGNMENT_STRATEGIES.filter((s) => s !== draft.strategy);

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">{saved ? 'Change settings' : 'Choose an assignment strategy'}</CardTitle>
        <CardDescription>This page stores configuration only. The routing engine reads it when assigning incidents.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} noValidate className="space-y-6">
          {formError && <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm font-medium text-destructive">{formError}</p>}

          <fieldset>
            <legend className="mb-2 text-sm font-medium leading-none">Assignment strategy</legend>
            <div className="grid gap-3 md:grid-cols-3">
              {ASSIGNMENT_STRATEGIES.map((s) => (
                <label
                  key={s}
                  className={cn(
                    'flex cursor-pointer flex-col gap-1 rounded-lg border bg-card p-4 transition-colors hover:bg-accent',
                    'has-[:checked]:border-primary has-[:checked]:bg-primary/5 has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring has-[:focus-visible]:ring-offset-2'
                  )}
                >
                  <span className="flex items-center gap-2 text-sm font-semibold">
                    <input type="radio" name="strategy" value={s} checked={draft.strategy === s} className="h-4 w-4 accent-primary"
                      onChange={() => patch({ strategy: s }, ['strategy', 'fallbackStrategy', 'teams', 'minSkillMatchPct'])} />
                    {strategyLabel(s)}
                  </span>
                  <span className="text-sm text-muted-foreground">{STRATEGY_INFO[s].description}</span>
                </label>
              ))}
            </div>
            {errors.strategy && <p role="alert" className="mt-2 text-sm font-medium text-destructive">{errors.strategy}</p>}
          </fieldset>

          {/* Strategy-specific configuration */}
          <section aria-label={`${strategyLabel(draft.strategy)} settings`} className="space-y-4 rounded-lg border bg-muted/40 p-4" data-testid={`settings-${draft.strategy}`}>
            <h3 className="text-sm font-semibold">{strategyLabel(draft.strategy)} settings</h3>

            {draft.strategy === 'MANUAL' && (
              <div className="flex items-center gap-2">
                <input id="manual-notify" type="checkbox" className="h-4 w-4 accent-primary" checked={draft.manual.notifyTeamLeads}
                  onChange={(e) => patch({ manual: { notifyTeamLeads: e.target.checked } })} />
                <Label htmlFor="manual-notify">Notify team leads when an incident is waiting for assignment</Label>
              </div>
            )}

            {draft.strategy === 'ROUND_ROBIN' && (
              <div className="space-y-4">
                <fieldset className="space-y-2">
                  <legend className="text-sm font-medium leading-none">Technician pool</legend>
                  {(Object.keys(POOL_LABEL) as RoundRobinPool[]).map((p) => (
                    <div key={p} className="flex items-center gap-2">
                      <input id={`pool-${p}`} type="radio" name="pool" className="h-4 w-4 accent-primary" checked={draft.roundRobin.pool === p}
                        onChange={() => patch({ roundRobin: { ...draft.roundRobin, pool: p } }, ['teams'])} />
                      <Label htmlFor={`pool-${p}`} className="font-normal">{POOL_LABEL[p]}</Label>
                    </div>
                  ))}
                </fieldset>
                {draft.roundRobin.pool === 'BY_TEAM' && (
                  <fieldset className="space-y-2">
                    <legend className="text-sm font-medium leading-none">Teams in the pool</legend>
                    <div className="flex flex-wrap gap-x-6 gap-y-2">
                      {TEAMS.map((t) => {
                        const on = draft.roundRobin.teams.includes(t);
                        return (
                          <div key={t} className="flex items-center gap-2">
                            <input id={`team-${t}`} type="checkbox" className="h-4 w-4 accent-primary" checked={on}
                              onChange={() => patch({ roundRobin: { ...draft.roundRobin, teams: on ? draft.roundRobin.teams.filter((x) => x !== t) : [...draft.roundRobin.teams, t] } }, ['teams'])} />
                            <Label htmlFor={`team-${t}`} className="font-normal">
                              {t}{!techTeams.has(t) && <span className="ml-1 text-xs text-muted-foreground">(no technicians)</span>}
                            </Label>
                          </div>
                        );
                      })}
                    </div>
                    {errors.teams && <p role="alert" className="text-sm font-medium text-destructive">{errors.teams}</p>}
                  </fieldset>
                )}
                <div className="flex items-center gap-2">
                  <input id="rr-skip" type="checkbox" className="h-4 w-4 accent-primary" checked={draft.roundRobin.skipAtCapacity}
                    onChange={(e) => patch({ roundRobin: { ...draft.roundRobin, skipAtCapacity: e.target.checked } })} />
                  <Label htmlFor="rr-skip">Skip technicians who are at their maximum workload</Label>
                </div>
              </div>
            )}

            {draft.strategy === 'SKILL_BASED' && (
              <div className="grid gap-4 sm:grid-cols-3">
                <FormField id="sb-match" label="Minimum skill match (%)" error={errors.minSkillMatchPct} hint="Share of the category’s skills a technician must have.">
                  <Input id="sb-match" inputMode="numeric" value={draft.skillBased.minSkillMatchPct} aria-invalid={!!errors.minSkillMatchPct}
                    aria-describedby={errors.minSkillMatchPct ? 'sb-match-error' : 'sb-match-hint'}
                    onChange={(e) => patch({ skillBased: { ...draft.skillBased, minSkillMatchPct: e.target.value } }, ['minSkillMatchPct'])} />
                </FormField>
                <FormField id="sb-level" label="Minimum skill level">
                  <Select value={draft.skillBased.minSkillLevel} onValueChange={(v) => patch({ skillBased: { ...draft.skillBased, minSkillLevel: v as SkillLevel } })}>
                    <SelectTrigger id="sb-level"><SelectValue /></SelectTrigger>
                    <SelectContent>{(Object.keys(SKILL_LEVEL_LABEL) as SkillLevel[]).map((l) => <SelectItem key={l} value={l}>{SKILL_LEVEL_LABEL[l]}</SelectItem>)}</SelectContent>
                  </Select>
                </FormField>
                <FormField id="sb-tie" label="Tie-breaker">
                  <Select value={draft.skillBased.tieBreaker} onValueChange={(v) => patch({ skillBased: { ...draft.skillBased, tieBreaker: v as TieBreaker } })}>
                    <SelectTrigger id="sb-tie"><SelectValue /></SelectTrigger>
                    <SelectContent>{(Object.keys(TIE_BREAKER_LABEL) as TieBreaker[]).map((t) => <SelectItem key={t} value={t}>{TIE_BREAKER_LABEL[t]}</SelectItem>)}</SelectContent>
                  </Select>
                </FormField>
              </div>
            )}

            {draft.strategy !== 'MANUAL' && (
              <FormField id="fallback" label="If no technician qualifies" error={errors.fallbackStrategy}>
                <Select value={draft.fallbackStrategy === draft.strategy ? '' : draft.fallbackStrategy} onValueChange={(v) => patch({ fallbackStrategy: v as AssignmentStrategy }, ['fallbackStrategy'])}>
                  <SelectTrigger id="fallback" className="sm:max-w-xs" aria-invalid={!!errors.fallbackStrategy}><SelectValue placeholder="Choose…" /></SelectTrigger>
                  <SelectContent>{fallbackOptions.map((s) => <SelectItem key={s} value={s}>Fall back to {strategyLabel(s).toLowerCase()}</SelectItem>)}</SelectContent>
                </Select>
              </FormField>
            )}
          </section>

          {/* Settings common to every strategy */}
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField id="escalation" label="Escalate unassigned incidents after (minutes)" error={errors.unassignedEscalationMinutes}
              hint={`Between ${MIN_ESCALATION} and ${MAX_ESCALATION}.`}>
              <Input id="escalation" inputMode="numeric" value={draft.unassignedEscalationMinutes} aria-invalid={!!errors.unassignedEscalationMinutes}
                aria-describedby={errors.unassignedEscalationMinutes ? 'escalation-error' : 'escalation-hint'}
                onChange={(e) => patch({ unassignedEscalationMinutes: e.target.value }, ['unassignedEscalationMinutes'])} />
            </FormField>
            <FormField id="recommendations" label="Technician recommendations shown" error={errors.recommendationCount}
              hint={`0 to ${MAX_RECOMMENDATIONS}. Use 0 to turn recommendations off.`}>
              <Input id="recommendations" inputMode="numeric" value={draft.recommendationCount} aria-invalid={!!errors.recommendationCount}
                aria-describedby={errors.recommendationCount ? 'recommendations-error' : 'recommendations-hint'}
                onChange={(e) => patch({ recommendationCount: e.target.value }, ['recommendationCount'])} />
            </FormField>
          </div>

          <div className="flex flex-wrap items-center justify-end gap-3 border-t pt-4">
            {saved && dirty && <p className="mr-auto text-sm text-muted-foreground" role="status">You have unsaved changes.</p>}
            {saved && <Button type="button" variant="outline" onClick={discard} disabled={!dirty || busy}>Discard changes</Button>}
            <Button type="submit" disabled={!dirty || busy}>
              {busy && <Loader2 className="animate-spin" aria-hidden="true" />}
              {saved ? 'Save changes' : 'Save configuration'}
            </Button>
          </div>
        </form>
      </CardContent>

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={saved ? `Switch to ${strategyLabel(current.strategy)}?` : ''}
        description={saved ? <>New incidents will be handled with <strong>{strategyLabel(current.strategy)}</strong> assignment instead of <strong>{strategyLabel(saved.strategy)}</strong>. Incidents already assigned are not affected.</> : null}
        confirmLabel="Switch strategy"
        onConfirm={commit}
      />
    </Card>
  );
}
