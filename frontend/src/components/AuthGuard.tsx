'use client';

import React, { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, usePathname } from 'next/navigation';
import { clearToken, getToken, setCachedUser } from '@/lib/auth';
import { api, ApiErrorResponse } from '@/lib/api';
import type { Role } from '@/types';
import { Button } from '@/components/ui/button';
import { AlertTriangle, Loader2, ShieldX } from 'lucide-react';

interface AuthGuardProps {
  children: React.ReactNode;
  /**
   * Role the signed-in user must have. It is read from the backend (`GET /api/users/me`) on every mount, never
   * from localStorage or the JWT, and nothing is rendered until the backend answers.
   *
   * This is a UX gate only: the backend must enforce authorization on every protected API.
   */
  requiredRole?: Role;
}

type State = 'checking' | 'allowed' | 'denied' | 'error';

export default function AuthGuard({ children, requiredRole }: AuthGuardProps) {
  const router = useRouter();
  const pathname = usePathname();
  const [state, setState] = useState<State>('checking');
  const [attempt, setAttempt] = useState(0);

  const retry = useCallback(() => {
    setState('checking');
    setAttempt((n) => n + 1);
  }, []);

  useEffect(() => {
    if (!getToken()) {
      router.replace(`/?redirect=${encodeURIComponent(pathname)}`);
      return;
    }
    if (!requiredRole) {
      setState('allowed');
      return;
    }

    let active = true;
    api
      .getMe()
      .then((user) => {
        if (!active) return;
        setCachedUser(user);
        setState(user?.role === requiredRole ? 'allowed' : 'denied');
      })
      .catch((err: unknown) => {
        if (!active) return;
        const status = err instanceof ApiErrorResponse ? err.status : undefined;
        if (status === 401) {
          // Expired or invalid token: drop it, or the sign-in page would bounce straight back to /dashboard.
          clearToken();
          router.replace(`/?redirect=${encodeURIComponent(pathname)}`);
        } else if (status === 403) {
          setState('denied');
        } else {
          setState('error');
        }
      });
    return () => {
      active = false;
    };
  }, [router, pathname, requiredRole, attempt]);

  if (state === 'allowed') return <>{children}</>;
  if (state === 'denied') return <AccessDenied />;
  if (state === 'error') return <VerificationFailed onRetry={retry} />;

  return (
    <div
      role="status"
      aria-live="polite"
      className="flex min-h-screen flex-col items-center justify-center gap-3 bg-background"
    >
      <Loader2 className="h-6 w-6 animate-spin text-primary" aria-hidden="true" />
      <p className="text-sm text-muted-foreground">Verifying authorization…</p>
    </div>
  );
}

function Notice({ icon, title, children }: { icon: React.ReactNode; title: string; children: React.ReactNode }) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-background p-4">
      <div className="w-full max-w-md rounded-lg border bg-card p-6 text-center shadow-sm sm:p-8">
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-lg bg-muted">{icon}</div>
        <h1 className="text-xl font-semibold tracking-tight text-foreground">{title}</h1>
        {children}
      </div>
    </main>
  );
}

function AccessDenied() {
  const router = useRouter();
  const signOut = () => {
    clearToken();
    router.replace('/');
  };
  return (
    <Notice icon={<ShieldX className="h-6 w-6 text-destructive" aria-hidden="true" />} title="Access denied">
      <p className="mt-2 text-sm text-muted-foreground">
        Your account doesn&apos;t have permission to view this page. If you need access, ask an administrator.
      </p>
      <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-center">
        <Button variant="outline" onClick={signOut}>
          Sign out
        </Button>
        <Button asChild>
          <Link href="/dashboard">Go to my dashboard</Link>
        </Button>
      </div>
    </Notice>
  );
}

function VerificationFailed({ onRetry }: { onRetry: () => void }) {
  return (
    <Notice icon={<AlertTriangle className="h-6 w-6 text-destructive" aria-hidden="true" />} title="Couldn't verify your access">
      <p className="mt-2 text-sm text-muted-foreground" role="alert">
        We couldn&apos;t reach the server to check your permissions. Check your connection and try again.
      </p>
      <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-center">
        <Button variant="outline" asChild>
          <Link href="/dashboard">Go to my dashboard</Link>
        </Button>
        <Button onClick={onRetry}>Try again</Button>
      </div>
    </Notice>
  );
}
