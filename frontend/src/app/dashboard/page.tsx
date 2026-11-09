'use client';

import React, { useEffect, useState } from 'react';
import AuthGuard from '@/components/AuthGuard';
import Navbar from '@/components/Navbar';
import { api } from '@/lib/api';
import { User } from '@/types';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { User as UserIcon, Mail, ShieldCheck, Calendar } from 'lucide-react';

/**
 * Everything that talks to the API lives here, inside <AuthGuard>, so it only mounts (and only calls
 * getMe()) once the guard has confirmed a token. Previously the fetch ran in the page component itself,
 * outside the guard, and fired an unauthenticated request whenever the guard was about to redirect.
 */
function DashboardContent() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api
      .getMe()
      .then(setUser)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, []);

  const details = user
    ? [
        { label: 'Full name', value: user.name, icon: UserIcon },
        { label: 'Email', value: user.email, icon: Mail },
        { label: 'Role', value: user.role, icon: ShieldCheck },
        {
          label: 'Member since',
          value: new Date(user.createdAt).toLocaleDateString('en-US', {
            year: 'numeric',
            month: 'long',
            day: 'numeric',
          }),
          icon: Calendar,
        },
      ]
    : [];

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <Navbar />

      <main className="mx-auto w-full max-w-4xl flex-1 px-4 py-10 sm:px-6 lg:px-8">
        <div className="mb-8">
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">Dashboard</h1>
          <p className="mt-1 text-sm text-muted-foreground">Your account overview.</p>
        </div>

        {loading ? (
          <Card role="status" aria-label="Loading profile">
            <CardContent className="p-6 sm:p-8">
              <div className="mb-8 flex items-center gap-4 border-b pb-8">
                <Skeleton className="h-14 w-14 rounded-lg" />
                <div className="space-y-2">
                  <Skeleton className="h-5 w-40" />
                  <Skeleton className="h-5 w-20" />
                </div>
              </div>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                {[0, 1, 2, 3].map((i) => (
                  <Skeleton key={i} className="h-[76px] w-full" />
                ))}
              </div>
            </CardContent>
          </Card>
        ) : user ? (
          <Card>
            <CardContent className="p-6 sm:p-8">
              {/* Avatar + name */}
              <div className="mb-8 flex items-center gap-4 border-b pb-8">
                <div
                  aria-hidden="true"
                  className="flex h-14 w-14 items-center justify-center rounded-lg bg-primary/10 text-2xl font-semibold text-primary"
                >
                  {user.name[0].toUpperCase()}
                </div>
                <div>
                  <p className="text-xl font-semibold text-foreground">{user.name}</p>
                  <Badge variant="secondary" className="mt-1 uppercase tracking-wide">
                    {user.role}
                  </Badge>
                </div>
              </div>

              {/* Profile details */}
              <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                {details.map(({ label, value, icon: Icon }) => (
                  <div key={label} className="min-w-0 rounded-lg border bg-muted/50 p-4">
                    <dt className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                      <Icon className="h-4 w-4" aria-hidden="true" />
                      {label}
                    </dt>
                    <dd className="mt-1.5 break-words text-sm font-medium text-foreground">{value}</dd>
                  </div>
                ))}
              </dl>
            </CardContent>
          </Card>
        ) : (
          <p className="text-muted-foreground">Could not load user profile.</p>
        )}
      </main>
    </div>
  );
}

export default function DashboardPage() {
  return (
    <AuthGuard>
      <DashboardContent />
    </AuthGuard>
  );
}
