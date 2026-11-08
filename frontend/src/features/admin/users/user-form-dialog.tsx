'use client';

import React, { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FormField } from '../components/form-field';
import { ROLE_LABEL } from '../components/status-badges';
import { userService, validateUserInput, type UserInput } from '../services/user-service';
import { DEPARTMENTS, type AdminRole, type ManagedUser } from '../types';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  /** Present = edit mode. */
  user?: ManagedUser | null;
  onSaved: (user: ManagedUser, mode: 'created' | 'updated') => void;
}

const EMPTY: UserInput = { name: '', email: '', role: 'EMPLOYEE', department: '' };

export function UserFormDialog({ open, onOpenChange, user, onSaved }: Props) {
  const editing = !!user;
  const [values, setValues] = useState<UserInput>(EMPTY);
  const [errors, setErrors] = useState<Partial<Record<keyof UserInput, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (open) {
      setValues(user ? { name: user.name, email: user.email, role: user.role, department: user.department } : EMPTY);
      setErrors({});
      setFormError(null);
    }
  }, [open, user]);

  const set = <K extends keyof UserInput>(k: K, v: UserInput[K]) => {
    setValues((p) => ({ ...p, [k]: v }));
    setErrors((p) => ({ ...p, [k]: undefined }));
  };

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const e = validateUserInput(values);
    setErrors(e);
    if (Object.keys(e).length) return;
    setBusy(true);
    setFormError(null);
    try {
      const saved = editing ? await userService.update(user!.id, values) : await userService.create(values);
      onSaved(saved, editing ? 'updated' : 'created');
      onOpenChange(false);
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not save the user.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{editing ? 'Edit user' : 'Add user'}</DialogTitle>
          <DialogDescription>
            {editing ? 'Update this person’s details and access.' : 'They will be invited by email and can sign in once they accept.'}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-4">
          {formError && (
            <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm font-medium text-destructive">
              {formError}
            </p>
          )}
          <FormField id="user-name" label="Full name" error={errors.name}>
            <Input
              id="user-name" value={values.name} autoComplete="off" aria-invalid={!!errors.name}
              aria-describedby={errors.name ? 'user-name-error' : undefined}
              onChange={(e) => set('name', e.target.value)}
            />
          </FormField>
          <FormField id="user-email" label="Email address" error={errors.email}>
            <Input
              id="user-email" type="email" value={values.email} autoComplete="off" aria-invalid={!!errors.email}
              aria-describedby={errors.email ? 'user-email-error' : undefined}
              onChange={(e) => set('email', e.target.value)}
            />
          </FormField>
          <div className="grid gap-4 sm:grid-cols-2">
            <FormField id="user-role" label="Role" error={errors.role}>
              <Select value={values.role} onValueChange={(v) => set('role', v as AdminRole)}>
                <SelectTrigger id="user-role"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {(Object.keys(ROLE_LABEL) as AdminRole[]).map((r) => (
                    <SelectItem key={r} value={r}>{ROLE_LABEL[r]}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </FormField>
            <FormField id="user-department" label="Department" error={errors.department}>
              <Select value={values.department} onValueChange={(v) => { set('department', v); setErrors((p) => ({ ...p, department: undefined })); }}>
                <SelectTrigger id="user-department" aria-invalid={!!errors.department}><SelectValue placeholder="Choose…" /></SelectTrigger>
                <SelectContent>
                  {DEPARTMENTS.map((d) => <SelectItem key={d} value={d}>{d}</SelectItem>)}
                </SelectContent>
              </Select>
            </FormField>
          </div>
          {values.role === 'TECHNICIAN' && (
            <p className="text-sm text-muted-foreground">
              A technician profile is created automatically. Add skills and workload limits under Technicians.
            </p>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2 className="animate-spin" aria-hidden="true" />}
              {editing ? 'Save changes' : 'Add user'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
