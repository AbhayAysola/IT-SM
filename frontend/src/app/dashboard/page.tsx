'use client';

import React, { useEffect, useState } from 'react';
import AuthGuard from '@/components/AuthGuard';
import Navbar from '@/components/Navbar';
import { api } from '@/lib/api';
import { User } from '@/types';
import { User as UserIcon, Mail, ShieldCheck, Calendar } from 'lucide-react';

export default function DashboardPage() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api
      .getMe()
      .then(setUser)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, []);

  return (
    <AuthGuard>
      <div className="min-h-screen bg-[#0a0a0f] text-slate-100 flex flex-col">
        <Navbar />

        <main className="flex-1 max-w-4xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-12">
          <div className="mb-8">
            <h1 className="text-2xl sm:text-3xl font-extrabold text-white tracking-tight">
              Dashboard
            </h1>
            <p className="text-sm text-slate-400 mt-1">Your account overview.</p>
          </div>

          {loading ? (
            <div className="flex items-center justify-center h-40">
              <div className="w-8 h-8 border-2 border-indigo-500 border-t-transparent rounded-full animate-spin" />
            </div>
          ) : user ? (
            <div className="rounded-2xl bg-[#13131a] border border-[#1f1f2e] p-6 sm:p-8 shadow-xl">
              {/* Avatar + name */}
              <div className="flex items-center gap-5 mb-8 pb-8 border-b border-[#1f1f2e]">
                <div className="w-16 h-16 rounded-2xl bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center text-white text-2xl font-bold shadow-lg shadow-indigo-600/30">
                  {user.name[0].toUpperCase()}
                </div>
                <div>
                  <p className="text-xl font-bold text-white">{user.name}</p>
                  <span className="inline-block mt-1 px-2.5 py-0.5 text-[11px] uppercase font-bold tracking-wider rounded-lg bg-indigo-500/15 text-indigo-300 border border-indigo-500/25">
                    {user.role}
                  </span>
                </div>
              </div>

              {/* Profile details */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                <div className="flex items-start gap-3 p-4 rounded-xl bg-[#0d0d14] border border-[#1f1f2e]">
                  <div className="w-9 h-9 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 flex items-center justify-center flex-shrink-0">
                    <UserIcon className="w-4 h-4" />
                  </div>
                  <div>
                    <p className="text-xs text-slate-500 uppercase tracking-wider font-medium">Full Name</p>
                    <p className="text-sm text-white font-medium mt-0.5">{user.name}</p>
                  </div>
                </div>

                <div className="flex items-start gap-3 p-4 rounded-xl bg-[#0d0d14] border border-[#1f1f2e]">
                  <div className="w-9 h-9 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 flex items-center justify-center flex-shrink-0">
                    <Mail className="w-4 h-4" />
                  </div>
                  <div>
                    <p className="text-xs text-slate-500 uppercase tracking-wider font-medium">Email</p>
                    <p className="text-sm text-white font-medium mt-0.5">{user.email}</p>
                  </div>
                </div>

                <div className="flex items-start gap-3 p-4 rounded-xl bg-[#0d0d14] border border-[#1f1f2e]">
                  <div className="w-9 h-9 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 flex items-center justify-center flex-shrink-0">
                    <ShieldCheck className="w-4 h-4" />
                  </div>
                  <div>
                    <p className="text-xs text-slate-500 uppercase tracking-wider font-medium">Role</p>
                    <p className="text-sm text-white font-medium mt-0.5">{user.role}</p>
                  </div>
                </div>

                <div className="flex items-start gap-3 p-4 rounded-xl bg-[#0d0d14] border border-[#1f1f2e]">
                  <div className="w-9 h-9 rounded-lg bg-indigo-500/10 border border-indigo-500/20 text-indigo-400 flex items-center justify-center flex-shrink-0">
                    <Calendar className="w-4 h-4" />
                  </div>
                  <div>
                    <p className="text-xs text-slate-500 uppercase tracking-wider font-medium">Member Since</p>
                    <p className="text-sm text-white font-medium mt-0.5">
                      {new Date(user.createdAt).toLocaleDateString('en-US', {
                        year: 'numeric',
                        month: 'long',
                        day: 'numeric',
                      })}
                    </p>
                  </div>
                </div>
              </div>
            </div>
          ) : (
            <p className="text-slate-400">Could not load user profile.</p>
          )}
        </main>
      </div>
    </AuthGuard>
  );
}
