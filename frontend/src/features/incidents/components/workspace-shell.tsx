'use client';

import React, { createContext, useContext, useEffect, useState } from 'react';
import AuthGuard from '@/components/AuthGuard';
import Navbar from '@/components/Navbar';
import { api } from '@/lib/api';
import { setCachedUser } from '@/lib/auth';
import { ErrorState } from '@/features/admin/components/states';
import { ToastProvider } from '@/features/admin/components/toast';
import { Skeleton } from '@/components/ui/skeleton';
import { toActorRole } from '../model/lifecycle';
import type { Actor } from '../model/types';

const ActorContext = createContext<Actor | null>(null);

/** The signed-in person with their role as the backend reports it. Only usable under <WorkspaceShell>. */
export function useActor(): Actor {
  const a = useContext(ActorContext);
  if (!a) throw new Error('useActor must be used inside <WorkspaceShell>');
  return a;
}

/**
 * The role comes from GET /api/users/me on every visit (never from storage or the token). This is UX, not security:
 * the backend must authorise every incident API. Until it issues real roles every account is `USER`, which is
 * treated as EMPLOYEE (least privilege), so technician and admin views cannot be reached against the real backend yet.
 */
function ActorLoader({ children }: { children: React.ReactNode }) {
  const [actor, setActor] = useState<Actor | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
    setError(null);
    api
      .getMe()
      .then((u) => {
        if (!live) return;
        setCachedUser(u);
        setActor({ email: u.email, name: u.name, role: toActorRole(u.role) });
      })
      .catch((e: unknown) => {
        if (live) setError(e instanceof Error ? e.message : 'Something went wrong.');
      });
    return () => { live = false; };
  }, [attempt]);

  if (error) return <ErrorState message={error} onRetry={() => setAttempt((n) => n + 1)} />;
  if (!actor) {
    return (
      <div role="status" aria-label="Loading your incidents" className="space-y-3">
        <Skeleton className="h-8 w-56" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }
  return <ActorContext.Provider value={actor}>{children}</ActorContext.Provider>;
}

export function WorkspaceShell({ children }: { children: React.ReactNode }) {
  return (
    <AuthGuard>
      <ToastProvider>
        <div className="flex min-h-screen flex-col bg-background">
          <Navbar />
          <main id="main" className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6 lg:px-8">
            <ActorLoader>{children}</ActorLoader>
          </main>
        </div>
      </ToastProvider>
    </AuthGuard>
  );
}
