'use client';

import React, { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FormError, withServerFields } from '../components/form-error';
import { FormField } from '../components/form-field';
import { ROLE_LABEL } from '../components/status-badges';
import { PASSWORD_MAX, PASSWORD_MIN, userApi, validateCreateUser, validateEditUser } from '../services/user-api';
import { ADMIN_ROLES, ServiceError, type AdminRole, type AdminUser } from '../types';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  /** Present = edit name/email. Role, active state and password have their own actions. */
  user?: AdminUser | null;
  onSaved: (user: AdminUser, mode: 'created' | 'updated') => void;
}

export function UserFormDialog({ open, onOpenChange, user, onSaved }: Props) {
  const editing = !!user;
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<AdminRole>('EMPLOYEE');
  const [version, setVersion] = useState(0);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [failure, setFailure] = useState<ServiceError | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setName(user?.name ?? ''); setEmail(user?.email ?? ''); setPassword(''); setRole('EMPLOYEE');
    setVersion(user?.version ?? 0); setErrors({}); setFailure(null);
  }, [open, user]);

  const clear = (k: string) => setErrors((p) => ({ ...p, [k]: '' }));

  const submit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const local = editing ? validateEditUser({ email, name }) : validateCreateUser({ email, name, password, role });
    setErrors(local);
    if (Object.keys(local).length) return;
    setBusy(true); setFailure(null);
    try {
      const saved = editing ? await userApi.update(user!.id, { email, name }, version) : await userApi.create({ email, name, password, role });
      setPassword('');
      onSaved(saved, editing ? 'updated' : 'created');
      onOpenChange(false);
    } catch (e) {
      const err = e instanceof ServiceError ? e : new ServiceError('UNAVAILABLE', 'Could not save the user.');
      setFailure(err);
      setErrors(withServerFields(local, err));
    } finally {
      setBusy(false);
    }
  };

  const keepMine = async () => {
    setBusy(true);
    try { setVersion((await userApi.get(user!.id)).version); setFailure(null); }
    catch (e) { setFailure(e instanceof ServiceError ? e : null); }
    finally { setBusy(false); }
  };
  const loadLatest = async () => {
    setBusy(true);
    try { const u = await userApi.get(user!.id); setName(u.name); setEmail(u.email); setVersion(u.version); setFailure(null); }
    catch (e) { setFailure(e instanceof ServiceError ? e : null); }
    finally { setBusy(false); }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{editing ? 'Edit user' : 'Add user'}</DialogTitle>
          <DialogDescription>
            {editing ? 'Change the name or sign-in email. Role, access and password are separate actions.' : 'Creates an active account. Share the initial password with the person securely; they can sign in straight away.'}
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} noValidate className="space-y-4">
          <FormError error={failure} onKeepMine={editing ? keepMine : undefined} onLoadLatest={editing ? loadLatest : undefined} busy={busy} />
          <FormField id="user-name" label="Full name" error={errors.name}>
            <Input id="user-name" value={name} autoComplete="off" aria-invalid={!!errors.name} aria-describedby={errors.name ? 'user-name-error' : undefined}
              onChange={(e) => { setName(e.target.value); clear('name'); }} />
          </FormField>
          <FormField id="user-email" label="Email address" error={errors.email}>
            <Input id="user-email" type="email" value={email} autoComplete="off" aria-invalid={!!errors.email} aria-describedby={errors.email ? 'user-email-error' : undefined}
              onChange={(e) => { setEmail(e.target.value); clear('email'); }} />
          </FormField>
          {!editing && (
            <>
              <FormField id="user-password" label="Initial password" error={errors.password} hint={`${PASSWORD_MIN}–${PASSWORD_MAX} characters.`}>
                <Input id="user-password" type="password" value={password} autoComplete="new-password" aria-invalid={!!errors.password}
                  aria-describedby={errors.password ? 'user-password-error' : 'user-password-hint'}
                  onChange={(e) => { setPassword(e.target.value); clear('password'); }} />
              </FormField>
              <FormField id="user-role" label="Role" error={errors.role}>
                <Select value={role} onValueChange={(v) => { setRole(v as AdminRole); clear('role'); }}>
                  <SelectTrigger id="user-role"><SelectValue /></SelectTrigger>
                  <SelectContent>{ADMIN_ROLES.map((r) => <SelectItem key={r} value={r}>{ROLE_LABEL[r]}</SelectItem>)}</SelectContent>
                </Select>
              </FormField>
              {role === 'TECHNICIAN' && <p className="text-sm text-muted-foreground">A technician profile is not created automatically. Add one under Technicians.</p>}
            </>
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
