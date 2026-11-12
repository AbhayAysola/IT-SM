'use client';

import React, { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FormError } from '../components/form-error';
import { FormField } from '../components/form-field';
import { ROLE_LABEL } from '../components/status-badges';
import { PASSWORD_MAX, PASSWORD_MIN, userApi, validatePassword } from '../services/user-api';
import { ADMIN_ROLES, ServiceError, type AdminRole, type AdminUser } from '../types';

const asError = (e: unknown) => (e instanceof ServiceError ? e : new ServiceError('UNAVAILABLE', 'Something went wrong. Please try again.'));

export function ChangeRoleDialog({ user, onClose, onSaved }: { user: AdminUser | null; onClose: () => void; onSaved: (u: AdminUser) => void }) {
  const [role, setRole] = useState<AdminRole>('EMPLOYEE');
  const [version, setVersion] = useState(0);
  const [failure, setFailure] = useState<ServiceError | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (user) { setRole(user.role); setVersion(user.version); setFailure(null); } }, [user]);

  const save = async () => {
    setBusy(true); setFailure(null);
    try { const u = await userApi.changeRole(user!.id, role, version); onSaved(u); onClose(); }
    catch (e) { setFailure(asError(e)); }
    finally { setBusy(false); }
  };
  const refresh = async () => {
    setBusy(true);
    try { const u = await userApi.get(user!.id); setVersion(u.version); setFailure(null); }
    catch (e) { setFailure(asError(e)); }
    finally { setBusy(false); }
  };

  return (
    <Dialog open={!!user} onOpenChange={(o) => !busy && !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Change role of {user?.name}</DialogTitle>
          <DialogDescription>
            Their current sessions are revoked, so they must sign in again. You cannot change your own role, and the last active administrator cannot be demoted.
          </DialogDescription>
        </DialogHeader>
        <FormError error={failure} onKeepMine={refresh} busy={busy} />
        <FormField id="role-select" label="Role">
          <Select value={role} onValueChange={(v) => setRole(v as AdminRole)}>
            <SelectTrigger id="role-select"><SelectValue /></SelectTrigger>
            <SelectContent>{ADMIN_ROLES.map((r) => <SelectItem key={r} value={r}>{ROLE_LABEL[r]}</SelectItem>)}</SelectContent>
          </Select>
        </FormField>
        <DialogFooter>
          <Button variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
          <Button onClick={save} disabled={busy || role === user?.role}>
            {busy && <Loader2 className="animate-spin" aria-hidden="true" />} Change role
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function ResetPasswordDialog({ user, onClose, onDone }: { user: AdminUser | null; onClose: () => void; onDone: (u: AdminUser) => void }) {
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | undefined>();
  const [failure, setFailure] = useState<ServiceError | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (user) { setPassword(''); setError(undefined); setFailure(null); } }, [user]);

  const save = async (ev: React.FormEvent) => {
    ev.preventDefault();
    const invalid = validatePassword(password);
    setError(invalid);
    if (invalid) return;
    setBusy(true); setFailure(null);
    try { const u = await userApi.resetPassword(user!.id, password); setPassword(''); onDone(u); onClose(); }
    catch (e) { const err = asError(e); setFailure(err); setError(err.fieldErrors.password); }
    finally { setBusy(false); }
  };

  return (
    <Dialog open={!!user} onOpenChange={(o) => !busy && !o && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Reset password for {user?.name}</DialogTitle>
          <DialogDescription>Sets a new password and signs them out everywhere. Share the new password with them securely; it is never shown again.</DialogDescription>
        </DialogHeader>
        <form onSubmit={save} noValidate className="space-y-4">
          <FormError error={failure} />
          <FormField id="reset-password" label="New password" error={error} hint={`${PASSWORD_MIN}–${PASSWORD_MAX} characters.`}>
            <Input id="reset-password" type="password" value={password} autoComplete="new-password" aria-invalid={!!error}
              aria-describedby={error ? 'reset-password-error' : 'reset-password-hint'} onChange={(e) => { setPassword(e.target.value); setError(undefined); }} />
          </FormField>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={busy}>Cancel</Button>
            <Button type="submit" variant="destructive" disabled={busy}>
              {busy && <Loader2 className="animate-spin" aria-hidden="true" />} Reset password
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
