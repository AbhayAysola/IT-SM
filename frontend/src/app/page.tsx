'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { api, ApiErrorResponse } from '@/lib/api';
import { setCachedUser, purgeLegacyCredentials } from '@/lib/auth';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { ShieldCheck, Mail, Lock, User as UserIcon, Loader2, AlertCircle } from 'lucide-react';

/** Shown after EVERY accepted registration: it must not hint at whether the address was already in use. */
const REGISTRATION_NOTICE =
  'Thanks. If this address can be used, your account is ready: sign in below. If you already have an account, sign in with it or ask an administrator to reset your password.';

/** 429 gets a calm, specific message; everything else keeps the backend's own message. */
function authErrorMessage(err: unknown): string {
  if (err instanceof ApiErrorResponse && err.status === 429) return 'Too many attempts. Please wait a few minutes and try again.';
  if (err instanceof ApiErrorResponse && err.status === 403) return 'This request was blocked. Reload the page and try again.';
  return err instanceof Error ? err.message : 'An error occurred during authentication';
}

/** Turns raw Jakarta validation messages into something a user can act on. */
function friendlyFieldError(field: string, raw: string): string {
  if (raw.includes('must not be blank')) return 'Required';
  if (raw.includes('must be a well-formed email address')) return 'Enter a valid email address';
  if (raw.includes('size must be between')) {
    if (field === 'password') return 'Must be at least 8 characters';
    return 'Too long';
  }
  return raw;
}

export default function LoginPage() {
  const router = useRouter();
  const [isLogin, setIsLogin] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  // Form states
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');

  // Already signed in? Ask the backend (the cookie is HttpOnly, so the client cannot tell by itself). Skipped when a guard just sent
  // us here (`?redirect=`): that only happens because the session was missing or rejected.
  useEffect(() => {
    purgeLegacyCredentials();
    if (new URLSearchParams(window.location.search).has('redirect')) return;
    let active = true;
    api
      .getMe()
      .then((user) => {
        if (!active) return;
        setCachedUser(user);
        router.replace('/dashboard');
      })
      .catch(() => {
        // 401 is the normal answer for a visitor who is not signed in; the form stays.
      });
    return () => {
      active = false;
    };
  }, [router]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setNotice(null);
    setFieldErrors({});
    setLoading(true);

    try {
      if (isLogin) {
        // Handle Login. The backend answers with an HttpOnly session cookie and `{user}`; there is no token for us to keep.
        const res = await api.login({ email, password });
        if (res.user) setCachedUser(res.user);
        router.replace('/dashboard');
      } else {
        // Handle Register. The backend answers the same 202 for a new and an already registered address, and never returns a
        // token, so this must NOT assume an account was created or sign the user in: say the same neutral thing every time.
        await api.register({ email, password, name });
        setIsLogin(true);
        setPassword('');
        setNotice(REGISTRATION_NOTICE);
      }
    } catch (err: unknown) {
      if (err instanceof ApiErrorResponse && err.fieldErrors.length > 0) {
        const mapped: Record<string, string> = {};
        for (const f of err.fieldErrors) mapped[f.field] = friendlyFieldError(f.field, f.message);
        setFieldErrors(mapped);
      } else {
        setError(authErrorMessage(err));
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="flex min-h-screen w-full items-center justify-center bg-background p-4 sm:p-6">
      <div className="w-full max-w-md">
        {/* Brand header */}
        <div className="mb-8 text-center">
          <div className="mb-3 flex items-center justify-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-primary text-primary-foreground">
              <ShieldCheck className="h-6 w-6" aria-hidden="true" />
            </div>
            <h1 className="text-3xl font-semibold tracking-tight text-foreground">IT-SM</h1>
          </div>
          <p className="text-sm text-muted-foreground">
            IT Service Management &amp; Incident Resolution
          </p>
        </div>

        <Card>
          <CardContent className="p-6 sm:p-8">
            {/* Sign in / Register toggle: a two-state button group (one shared form, so not ARIA tabs) */}
            <div
              role="group"
              aria-label="Authentication mode"
              className="mb-6 grid grid-cols-2 gap-1 rounded-md bg-muted p-1"
            >
              {([['Sign In', true], ['Register', false]] as const).map(([label, mode]) => (
                <Button
                  key={label}
                  type="button"
                  variant="ghost"
                  size="sm"
                  aria-pressed={isLogin === mode}
                  onClick={() => {
                    setIsLogin(mode);
                    setError(null);
                    setNotice(null);
                    setFieldErrors({});
                  }}
                  className={cn(
                    'h-8 text-sm',
                    isLogin === mode
                      ? 'bg-card text-foreground shadow-sm hover:bg-card'
                      : 'text-muted-foreground hover:bg-transparent hover:text-foreground'
                  )}
                >
                  {label}
                </Button>
              ))}
            </div>

            {/* Alert message */}
            {error && (
              <div
                role="alert"
                className="mb-6 flex items-start gap-2.5 rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive"
              >
                <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                <div className="flex-1 font-medium leading-relaxed">{error}</div>
              </div>
            )}

            {/* Neutral, identical for every registration outcome (see REGISTRATION_NOTICE) */}
            {notice && (
              <div
                role="status"
                className="mb-6 rounded-md border border-border bg-muted p-3 text-sm font-medium leading-relaxed text-foreground"
              >
                {notice}
              </div>
            )}

            {/* Auth form */}
            <form onSubmit={handleSubmit} className="space-y-4">
              {!isLogin && (
                <div className="space-y-1.5">
                  <Label htmlFor="name">Full name</Label>
                  <div className="relative">
                    <UserIcon className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-muted-foreground" aria-hidden="true" />
                    <Input
                      id="name"
                      type="text"
                      required={!isLogin}
                      autoComplete="name"
                      value={name}
                      onChange={(e) => { setName(e.target.value); setFieldErrors((p) => { const n = { ...p }; delete n.name; return n; }); }}
                      placeholder="Jane Doe"
                      aria-invalid={!!fieldErrors.name}
                      aria-describedby={fieldErrors.name ? 'name-error' : undefined}
                      className={cn('pl-9', fieldErrors.name && 'border-destructive focus-visible:ring-destructive')}
                    />
                  </div>
                  {fieldErrors.name && <p id="name-error" className="text-xs text-destructive">{fieldErrors.name}</p>}
                </div>
              )}

              <div className="space-y-1.5">
                <Label htmlFor="email">Email address</Label>
                <div className="relative">
                  <Mail className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-muted-foreground" aria-hidden="true" />
                  <Input
                    id="email"
                    type="email"
                    required
                    autoComplete="email"
                    value={email}
                    onChange={(e) => { setEmail(e.target.value); setFieldErrors((p) => { const n = { ...p }; delete n.email; return n; }); }}
                    placeholder="name@company.com"
                    aria-invalid={!!fieldErrors.email}
                    aria-describedby={fieldErrors.email ? 'email-error' : undefined}
                    className={cn('pl-9', fieldErrors.email && 'border-destructive focus-visible:ring-destructive')}
                  />
                </div>
                {fieldErrors.email && <p id="email-error" className="text-xs text-destructive">{fieldErrors.email}</p>}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="password">Password</Label>
                <div className="relative">
                  <Lock className="pointer-events-none absolute left-3 top-2.5 h-4 w-4 text-muted-foreground" aria-hidden="true" />
                  <Input
                    id="password"
                    type="password"
                    required
                    autoComplete={isLogin ? 'current-password' : 'new-password'}
                    value={password}
                    onChange={(e) => { setPassword(e.target.value); setFieldErrors((p) => { const n = { ...p }; delete n.password; return n; }); }}
                    placeholder="••••••••••••"
                    aria-invalid={!!fieldErrors.password}
                    aria-describedby={fieldErrors.password ? 'password-error' : undefined}
                    className={cn('pl-9', fieldErrors.password && 'border-destructive focus-visible:ring-destructive')}
                  />
                </div>
                {fieldErrors.password && <p id="password-error" className="text-xs text-destructive">{fieldErrors.password}</p>}
              </div>

              <Button type="submit" size="lg" disabled={loading} className="mt-2 w-full">
                {loading ? (
                  <>
                    <Loader2 className="animate-spin" aria-hidden="true" />
                    <span>Processing…</span>
                  </>
                ) : (
                  <span>{isLogin ? 'Sign in' : 'Create account'}</span>
                )}
              </Button>
            </form>
          </CardContent>
        </Card>
      </div>
    </main>
  );
}
