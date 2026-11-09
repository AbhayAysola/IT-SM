'use client';

import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { api } from '@/lib/api';
import { setToken, setCachedUser, isAuthenticated } from '@/lib/auth';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { ShieldCheck, Mail, Lock, User as UserIcon, Loader2, AlertCircle } from 'lucide-react';

export default function LoginPage() {
  const router = useRouter();
  const [isLogin, setIsLogin] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Form states
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');

  // Auto redirect if already authenticated
  useEffect(() => {
    if (isAuthenticated()) {
      router.replace('/dashboard');
    }
  }, [router]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      if (isLogin) {
        // Handle Login
        const res = await api.login({ email, password });
        if (res.token) {
          setToken(res.token);
          if (res.user) {
            setCachedUser(res.user);
          }
          router.replace('/dashboard');
        } else {
          throw new Error('No authentication token received');
        }
      } else {
        // Handle Register
        const res = await api.register({ email, password, name });
        if (res.token) {
          setToken(res.token);
          if (res.user) {
            setCachedUser(res.user);
          }
          router.replace('/dashboard');
        } else {
          // If register returns message instead of direct token, switch to login
          setIsLogin(true);
          setError('Registration successful! Please sign in with your credentials.');
        }
      }
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'An error occurred during authentication';
      setError(msg);
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
                      onChange={(e) => setName(e.target.value)}
                      placeholder="Jane Doe"
                      className="pl-9"
                    />
                  </div>
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
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="name@company.com"
                    className="pl-9"
                  />
                </div>
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
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="••••••••••••"
                    className="pl-9"
                  />
                </div>
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
