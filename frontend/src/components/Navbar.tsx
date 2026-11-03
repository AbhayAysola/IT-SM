'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { clearToken, getCachedUser, setCachedUser } from '@/lib/auth';
import { api } from '@/lib/api';
import { User } from '@/types';
import {
  LayoutDashboard,
  LogOut,
  User as UserIcon,
  ShieldCheck,
  ChevronDown,
} from 'lucide-react';

export default function Navbar() {
  const pathname = usePathname();
  const router = useRouter();
  const [user, setUser] = useState<User | null>(getCachedUser());
  const [dropdownOpen, setDropdownOpen] = useState(false);

  useEffect(() => {
    let isMounted = true;
    api
      .getMe()
      .then((userData) => {
        if (isMounted && userData) {
          setUser(userData);
          setCachedUser(userData);
        }
      })
      .catch((err) => {
        console.warn('Could not load current user profile:', err);
      });

    return () => {
      isMounted = false;
    };
  }, []);

  const handleLogout = () => {
    clearToken();
    router.replace('/');
  };

  const navLinks = [
    {
      name: 'Dashboard',
      href: '/dashboard',
      icon: LayoutDashboard,
    },
  ];

  return (
    <nav className="sticky top-0 z-50 w-full border-b border-[#1f1f2e] bg-[#0d0d14]/80 backdrop-blur-xl">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="flex items-center justify-between h-16">
          {/* Brand Logo */}
          <div className="flex items-center gap-8">
            <Link
              href="/dashboard"
              className="flex items-center gap-2.5 text-white font-bold text-lg tracking-tight group"
            >
              <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-indigo-600 to-violet-500 flex items-center justify-center shadow-glow-sm group-hover:shadow-glow transition-all duration-300">
                <ShieldCheck className="w-5 h-5 text-white" />
              </div>
              <span className="bg-gradient-to-r from-white via-slate-200 to-indigo-300 bg-clip-text text-transparent font-extrabold tracking-wide">
                IT-SM
              </span>
            </Link>

            {/* Desktop Navigation Links */}
            <div className="hidden md:flex items-center space-x-1">
              {navLinks.map((link) => {
                const Icon = link.icon;
                const isActive = pathname === link.href;
                return (
                  <Link
                    key={link.href}
                    href={link.href}
                    className={`flex items-center gap-2 px-3.5 py-2 rounded-lg text-sm font-medium transition-all duration-200 ${
                      isActive
                        ? 'bg-indigo-600/15 text-indigo-400 border border-indigo-500/30 shadow-inner'
                        : 'text-slate-400 hover:text-slate-100 hover:bg-[#1a1a26]'
                    }`}
                  >
                    <Icon className={`w-4 h-4 ${isActive ? 'text-indigo-400' : 'text-slate-400'}`} />
                    {link.name}
                  </Link>
                );
              })}
            </div>
          </div>

          {/* User profile & Actions */}
          <div className="flex items-center gap-3">
            {/* User Info Capsule */}
            <div className="relative">
              <button
                onClick={() => setDropdownOpen(!dropdownOpen)}
                className="flex items-center gap-2.5 px-3 py-1.5 rounded-xl border border-[#222234] bg-[#14141e] hover:bg-[#1c1c2b] transition-all text-left"
              >
                <div className="w-7 h-7 rounded-lg bg-indigo-500/20 text-indigo-400 flex items-center justify-center font-semibold text-xs border border-indigo-500/30">
                  {user?.name ? user.name[0].toUpperCase() : <UserIcon className="w-3.5 h-3.5" />}
                </div>
                <div className="hidden sm:block text-left">
                  <p className="text-xs font-semibold text-slate-200 leading-none">
                    {user?.name || 'Administrator'}
                  </p>
                  <p className="text-[11px] text-slate-400 leading-tight mt-0.5 truncate max-w-[140px]">
                    {user?.email || 'user@example.com'}
                  </p>
                </div>
                <ChevronDown className="w-3.5 h-3.5 text-slate-400 hidden sm:block ml-0.5" />
              </button>

              {/* Dropdown Menu */}
              {dropdownOpen && (
                <div className="absolute right-0 mt-2 w-56 rounded-xl border border-[#26263b] bg-[#141420] shadow-2xl py-2 z-50">
                  <div className="px-4 py-2 border-b border-[#232338]">
                    <p className="text-xs font-medium text-slate-400">Signed in as</p>
                    <p className="text-sm font-semibold text-white truncate mt-0.5">
                      {user?.email || 'user@example.com'}
                    </p>
                    {user?.role && (
                      <span className="inline-block mt-1 px-2 py-0.5 text-[10px] uppercase font-bold tracking-wider rounded bg-indigo-500/20 text-indigo-300 border border-indigo-500/30">
                        {user.role}
                      </span>
                    )}
                  </div>

                  <div className="md:hidden py-1 border-b border-[#232338]">
                    {navLinks.map((link) => (
                      <Link
                        key={link.href}
                        href={link.href}
                        onClick={() => setDropdownOpen(false)}
                        className="block px-4 py-2 text-sm text-slate-300 hover:bg-[#1e1e30] hover:text-white"
                      >
                        {link.name}
                      </Link>
                    ))}
                  </div>

                  <button
                    onClick={handleLogout}
                    className="w-full flex items-center gap-2.5 px-4 py-2.5 text-sm text-rose-400 hover:bg-rose-500/10 hover:text-rose-300 transition-colors"
                  >
                    <LogOut className="w-4 h-4" />
                    Sign out
                  </button>
                </div>
              )}
            </div>

            {/* Logout icon button */}
            <button
              onClick={handleLogout}
              title="Logout"
              className="p-2 rounded-xl text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 border border-transparent hover:border-rose-500/20 transition-all duration-200"
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>
    </nav>
  );
}
