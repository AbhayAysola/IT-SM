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
      <div className="min-h-screen bg-[#0a0a0f] flex flex-col items-center justify-center space-y-4">
        <div className="relative">
          <div className="w-12 h-12 rounded-full border-2 border-indigo-500/20 border-t-indigo-500 animate-spin" />
          <Loader2 className="w-6 h-6 text-indigo-400 absolute inset-0 m-auto animate-pulse" />
        </div>
        <p className="text-sm text-slate-400 font-medium tracking-wide">
          Verifying authorization...
        </p>
      </div>
    );
  }

  return <>{children}</>;
}
