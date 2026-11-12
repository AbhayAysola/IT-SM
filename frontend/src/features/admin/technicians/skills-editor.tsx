'use client';

import React from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { TECH_LIMITS, type SkillInput } from '../services/technician-api';

export interface CategoryOption { id: number; label: string }

/** Rows of (category, proficiency 1-5). Errors use the server's field names: `skills[i].categoryId`, `skills[i].proficiency`. */
export function SkillsEditor({
  skills, onChange, options, errors, idPrefix,
}: { skills: SkillInput[]; onChange: (s: SkillInput[]) => void; options: CategoryOption[]; errors: Record<string, string>; idPrefix: string }) {
  const used = new Set(skills.map((s) => s.categoryId));
  const free = options.filter((o) => !used.has(o.id));
  return (
    <fieldset className="space-y-2">
      <legend className="text-sm font-medium">Skills</legend>
      <p className="text-xs text-muted-foreground">A skill is an incident category with a proficiency from 1 (basic) to 5 (expert).</p>
      {skills.length === 0 && <p className="text-sm text-muted-foreground" data-testid="no-skills">No skills yet.</p>}
      {skills.map((s, i) => {
        const catErr = errors[`skills[${i}].categoryId`];
        const profErr = errors[`skills[${i}].proficiency`];
        const label = options.find((o) => o.id === s.categoryId)?.label ?? `Category ${s.categoryId}`;
        return (
          <div key={i} className="flex flex-wrap items-start gap-2" data-testid="skill-row">
            <div className="min-w-[10rem] flex-1">
              <Select value={s.categoryId ? String(s.categoryId) : ''} onValueChange={(v) => onChange(skills.map((x, j) => (j === i ? { ...x, categoryId: Number(v) } : x)))}>
                <SelectTrigger id={`${idPrefix}-cat-${i}`} aria-label={`Skill ${i + 1} category`} aria-invalid={!!catErr}><SelectValue placeholder="Choose category…" /></SelectTrigger>
                <SelectContent>
                  {[{ id: s.categoryId, label }, ...free].filter((o) => o.id).map((o) => <SelectItem key={o.id} value={String(o.id)}>{o.label}</SelectItem>)}
                </SelectContent>
              </Select>
              {catErr && <p role="alert" className="mt-1 text-sm font-medium text-destructive">{catErr}</p>}
            </div>
            <div className="w-32">
              <Select value={String(s.proficiency)} onValueChange={(v) => onChange(skills.map((x, j) => (j === i ? { ...x, proficiency: Number(v) } : x)))}>
                <SelectTrigger id={`${idPrefix}-lvl-${i}`} aria-label={`Skill ${i + 1} proficiency`} aria-invalid={!!profErr}><SelectValue /></SelectTrigger>
                <SelectContent>{[1, 2, 3, 4, 5].map((n) => <SelectItem key={n} value={String(n)}>Level {n}</SelectItem>)}</SelectContent>
              </Select>
              {profErr && <p role="alert" className="mt-1 text-sm font-medium text-destructive">{profErr}</p>}
            </div>
            <Button type="button" variant="ghost" size="icon" aria-label={`Remove skill ${i + 1}`} onClick={() => onChange(skills.filter((_, j) => j !== i))}>
              <Trash2 aria-hidden="true" />
            </Button>
          </div>
        );
      })}
      {errors.skills && <p role="alert" className="text-sm font-medium text-destructive">{errors.skills}</p>}
      <Button type="button" variant="outline" size="sm" disabled={free.length === 0 || skills.length >= TECH_LIMITS.skills}
        onClick={() => onChange([...skills, { categoryId: free[0].id, proficiency: 3 }])}>
        <Plus aria-hidden="true" /> Add skill
      </Button>
    </fieldset>
  );
}
