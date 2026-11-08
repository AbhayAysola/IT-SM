'use client';

import React, { useEffect, useState } from 'react';
import { Loader2, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FormField } from '../components/form-field';
import { Badge } from '@/components/ui/badge';
import {
  categoryService, MAX_KEYWORDS, normalizeKeyword, validateCategoryInput, validateKeyword, type CategoryInput,
} from '../services/category-service';
import { PRIORITIES, PRIORITY_LABEL, type IncidentCategory, type Priority } from '../types';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  category?: IncidentCategory | null;
  onSaved: (c: IncidentCategory, mode: 'created' | 'updated') => void;
}

const EMPTY: CategoryInput = { name: '', description: '', keywords: [], defaultPriority: 'P3' };
type Errors = Partial<Record<keyof CategoryInput, string>>;

export function CategoryFormDialog({ open, onOpenChange, category, onSaved }: Props) {
  const editing = !!category;
  const [values, setValues] = useState<CategoryInput>(EMPTY);
  const [draft, setDraft] = useState('');
  const [errors, setErrors] = useState<Errors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setValues(category ? { name: category.name, description: category.description, keywords: category.keywords, defaultPriority: category.defaultPriority } : EMPTY);
    setDraft('');
    setErrors({});
    setFormError(null);
  }, [open, category]);

  const set = <K extends keyof CategoryInput>(k: K, v: CategoryInput[K]) => {
    setValues((p) => ({ ...p, [k]: v }));
    setErrors((p) => ({ ...p, [k]: undefined }));
  };

  /** Adds one or more comma-separated keywords from the draft; returns false if any was rejected. */
  const commitDraft = (): boolean => {
    const parts = draft.split(',').map(normalizeKeyword).filter(Boolean);
    if (parts.length === 0) return true;
    let next = values.keywords;
    for (const k of parts) {
      const bad = validateKeyword(k);
      if (bad) { setErrors((p) => ({ ...p, keywords: bad })); return false; }
      if (next.includes(k)) { setErrors((p) => ({ ...p, keywords: `“${k}” is already in the list.` })); return false; }
      if (next.length >= MAX_KEYWORDS) { setErrors((p) => ({ ...p, keywords: `Use at most ${MAX_KEYWORDS} keywords.` })); return false; }
      next = [...next, k];
    }
    set('keywords', next);
    setDraft('');
    setErrors((p) => ({ ...p, keywords: undefined }));
    return true;
  };

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    if (!commitDraft()) return;
    // commitDraft may have appended; recompute from the draft synchronously for validation
    const pending = draft.split(',').map(normalizeKeyword).filter(Boolean);
    const final = { ...values, keywords: [...values.keywords, ...pending.filter((k) => !values.keywords.includes(k))] };
    const e = validateCategoryInput(final);
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setFormError(null);
    try {
      const saved = editing ? await categoryService.update(category!.id, final) : await categoryService.create(final);
      onSaved(saved, editing ? 'updated' : 'created');
      onOpenChange(false);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not save the category.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{editing ? 'Edit category' : 'Add category'}</DialogTitle>
          <DialogDescription>Keywords help the system suggest this category when someone describes a problem.</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-4">
          {formError && <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm font-medium text-destructive">{formError}</p>}
          <FormField id="cat-name" label="Name" error={errors.name}>
            <Input id="cat-name" value={values.name} aria-invalid={!!errors.name} aria-describedby={errors.name ? 'cat-name-error' : undefined}
              onChange={(e) => set('name', e.target.value)} />
          </FormField>
          <FormField id="cat-description" label="Description" error={errors.description} hint={`${values.description.trim().length}/300`}>
            <textarea id="cat-description" rows={3} value={values.description} aria-invalid={!!errors.description}
              aria-describedby={errors.description ? 'cat-description-error' : 'cat-description-hint'}
              onChange={(e) => set('description', e.target.value)}
              className="flex w-full rounded-md border border-input bg-transparent px-3 py-2 text-base shadow-sm placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background aria-[invalid=true]:border-destructive md:text-sm" />
          </FormField>
          <FormField id="cat-priority" label="Default priority">
            <Select value={values.defaultPriority} onValueChange={(v) => set('defaultPriority', v as Priority)}>
              <SelectTrigger id="cat-priority"><SelectValue /></SelectTrigger>
              <SelectContent>{PRIORITIES.map((p) => <SelectItem key={p} value={p}>{PRIORITY_LABEL[p]}</SelectItem>)}</SelectContent>
            </Select>
          </FormField>
          <FormField id="cat-keyword" label="Keywords" error={errors.keywords} hint={`Press Enter or comma to add. ${values.keywords.length}/${MAX_KEYWORDS} used.`}>
            <Input id="cat-keyword" value={draft} placeholder="e.g. no internet" aria-invalid={!!errors.keywords}
              aria-describedby={errors.keywords ? 'cat-keyword-error' : 'cat-keyword-hint'}
              onChange={(e) => { setDraft(e.target.value); if (errors.keywords) setErrors((p) => ({ ...p, keywords: undefined })); }}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); commitDraft(); } }} />
          </FormField>
          {values.keywords.length > 0 && (
            <ul className="flex flex-wrap gap-1.5" aria-label="Added keywords">
              {values.keywords.map((k) => (
                <li key={k}>
                  <Badge variant="secondary" className="gap-1 pr-1">
                    {k}
                    <button type="button" aria-label={`Remove keyword ${k}`} onClick={() => set('keywords', values.keywords.filter((x) => x !== k))}
                      className="rounded p-0.5 hover:bg-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                      <X className="h-3 w-3" aria-hidden="true" />
                    </button>
                  </Badge>
                </li>
              ))}
            </ul>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
            <Button type="submit" disabled={busy}>{busy && <Loader2 className="animate-spin" aria-hidden="true" />}{editing ? 'Save changes' : 'Add category'}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
