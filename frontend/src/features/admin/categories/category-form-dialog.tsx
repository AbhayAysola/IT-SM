'use client';

import React, { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { FormError, withServerFields } from '../components/form-error';
import { FormField } from '../components/form-field';
import { categoryApi, parseKeywords, validateCategory, type CategoryForm } from '../services/category-api';
import { ServiceError, type Category } from '../types';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  category?: Category | null;
  onSaved: (c: Category, mode: 'created' | 'updated') => void;
}

const EMPTY: CategoryForm = { code: '', name: '', description: '', keywords: '' };
const asError = (e: unknown) => (e instanceof ServiceError ? e : new ServiceError('UNAVAILABLE', 'Could not save the category.'));
const toForm = (c: Category): CategoryForm => ({ code: c.code, name: c.name, description: c.description ?? '', keywords: c.keywords.join(', ') });
const TEXTAREA = 'flex min-h-[76px] w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring aria-[invalid=true]:border-destructive';

export function CategoryFormDialog({ open, onOpenChange, category, onSaved }: Props) {
  const editing = !!category;
  const [v, setV] = useState<CategoryForm>(EMPTY);
  const [version, setVersion] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ServiceError | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setV(category ? toForm(category) : EMPTY); setVersion(category?.version ?? 0); setErrors({}); setFailure(null);
  }, [open, category]);

  const set = (k: keyof CategoryForm, val: string) => { setV((p) => ({ ...p, [k]: val })); setErrors((p) => ({ ...p, [k]: '' })); };

  // The server reports keyword problems per entry (`keywords[2]`); show them on the one keywords field.
  const fold = (errs: Record<string, string>) => {
    const out = { ...errs };
    for (const k of Object.keys(out)) if (k.startsWith('keywords[')) { out.keywords = out.keywords || `${parseKeywords(v.keywords)[Number(k.slice(9, -1))] ?? 'A keyword'}: ${out[k]}`; delete out[k]; }
    return out;
  };

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const local = validateCategory(v, editing ? 'edit' : 'create');
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true); setFailure(null);
    try {
      const saved = editing ? await categoryApi.update(category!.id, v, version) : await categoryApi.create(v);
      onSaved(saved, editing ? 'updated' : 'created');
      onOpenChange(false);
    } catch (e) {
      const err = asError(e); setFailure(err); setErrors(fold(withServerFields(local, err)));
    } finally { setBusy(false); }
  };
  const keepMine = async () => {
    setBusy(true);
    try { setVersion((await categoryApi.get(category!.id)).version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };
  const loadLatest = async () => {
    setBusy(true);
    try { const c = await categoryApi.get(category!.id); setV(toForm(c)); setVersion(c.version); setFailure(null); } catch (e) { setFailure(asError(e)); } finally { setBusy(false); }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{editing ? 'Edit category' : 'Add category'}</DialogTitle>
          <DialogDescription>Incident categories are matched against ticket text using their keywords. Priority is not set here.</DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-4">
          <FormError error={failure} onKeepMine={editing ? keepMine : undefined} onLoadLatest={editing ? loadLatest : undefined} busy={busy} />
          <FormField id="cat-code" label="Code" error={errors.code} hint={editing ? 'The code is permanent and cannot be changed.' : 'Permanent identifier, UPPER_SNAKE_CASE, e.g. NETWORK. Unique ignoring case.'}>
            <Input id="cat-code" value={v.code} disabled={editing} autoComplete="off" aria-invalid={!!errors.code} aria-describedby={errors.code ? 'cat-code-error' : 'cat-code-hint'}
              onChange={(e) => set('code', e.target.value.toUpperCase())} />
          </FormField>
          <FormField id="cat-name" label="Name" error={errors.name}>
            <Input id="cat-name" value={v.name} autoComplete="off" aria-invalid={!!errors.name} aria-describedby={errors.name ? 'cat-name-error' : undefined} onChange={(e) => set('name', e.target.value)} />
          </FormField>
          <FormField id="cat-desc" label="Description (optional)" error={errors.description}>
            <textarea id="cat-desc" className={TEXTAREA} value={v.description} aria-invalid={!!errors.description} aria-describedby={errors.description ? 'cat-desc-error' : undefined} onChange={(e) => set('description', e.target.value)} />
          </FormField>
          <FormField id="cat-keywords" label="Keywords" error={errors.keywords} hint="Separate with commas or new lines. 1–15 keywords of 2–30 characters; stored lower-case.">
            <textarea id="cat-keywords" className={TEXTAREA} value={v.keywords} aria-invalid={!!errors.keywords} aria-describedby={errors.keywords ? 'cat-keywords-error' : 'cat-keywords-hint'} onChange={(e) => set('keywords', e.target.value)} />
          </FormField>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
            <Button type="submit" disabled={busy}>{busy && <Loader2 className="animate-spin" aria-hidden="true" />}{editing ? 'Save changes' : 'Add category'}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
