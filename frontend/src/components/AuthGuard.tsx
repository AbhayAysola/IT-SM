'use client';

import React, { useEffect, useState } from 'react';
import { useRouter, usePathname } from 'next/navigation';
import { getToken } from '@/lib/auth';
import { Loader2 } from 'lucide-react';

interface AuthGuardProps {
  children: React.ReactNode;
}

export default function AuthGuard({ children }: AuthGuardProps) {
  const router = useRouter();
  const pathname = usePathname();
  const [authorized, setAuthorized] = useState<boolean>(false);
  const [isChecking, setIsChecking] = useState<boolean>(true);

  useEffect(() => {
    const token = getToken();

    if (!token) {
      // Not authenticated, redirect to landing / login
      setIsChecking(false);
      router.replace(`/?redirect=${encodeURIComponent(pathname)}`);
    } else {
      setAuthorized(true);
      setIsChecking(false);
    }
  }, [router, pathname]);

  if (isChecking || !authorized) {
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

  return <>{children}</>;
}
