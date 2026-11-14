'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { getCachedUser, setCachedUser } from '@/lib/auth';
import { endSession } from '@/lib/session';
import { api } from '@/lib/api';
import { User } from '@/types';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import { NotificationBell } from '@/features/notifications/notification-bell';
import {
  ClipboardList,
  LayoutDashboard,
  LogOut,
  User as UserIcon,
  ShieldCheck,
  Settings2,
  ChevronDown,
} from 'lucide-react';

// Navigation family tokens (deep navy): bg-sidebar / text-sidebar-* / border-sidebar-border.
const navFocus =
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring focus-visible:ring-offset-2 focus-visible:ring-offset-sidebar';

export default function Navbar() {
  const pathname = usePathname();
  const router = useRouter();
  const [user, setUser] = useState<User | null>(getCachedUser());
  // Only the live /api/users/me answer may reveal admin navigation; the cached user is display-only.
  const [isAdmin, setIsAdmin] = useState(false);

  useEffect(() => {
    let isMounted = true;
    api
      .getMe()
      .then((userData) => {
        if (isMounted && userData) {
          setUser(userData);
          setCachedUser(userData);
          setIsAdmin(userData.role === 'ADMIN');
        }
      })
      .catch((err) => {
        console.warn('Could not load current user profile:', err);
      });

    return () => {
      isMounted = false;
    };
  }, []);

  const handleLogout = async () => {
    await endSession();
    router.replace('/');
  };

  const navLinks = [
    {
      name: 'Dashboard',
      href: '/dashboard',
      icon: LayoutDashboard,
    },
    {
      name: 'Incidents',
      href: '/incidents',
      icon: ClipboardList,
    },
    ...(isAdmin
      ? [
          {
            name: 'Administration',
            href: '/admin',
            icon: Settings2,
          },
        ]
      : []),
  ];

  return (
    <header className="sticky top-0 z-50 w-full border-b border-sidebar-border bg-sidebar text-sidebar-foreground">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex h-14 items-center justify-between">
          {/* Brand + primary navigation */}
          <div className="flex items-center gap-6">
            <Link
              href="/dashboard"
              className={cn('flex items-center gap-2.5 rounded-md text-base font-semibold tracking-tight text-white', navFocus)}
            >
              <span className="flex h-8 w-8 items-center justify-center rounded-md bg-sidebar-primary text-sidebar-primary-foreground">
                <ShieldCheck className="h-5 w-5" aria-hidden="true" />
              </span>
              IT-SM
            </Link>

            <nav aria-label="Primary" className="hidden items-center gap-1 md:flex">
              {navLinks.map((link) => {
                const Icon = link.icon;
                const isActive = pathname === link.href || pathname.startsWith(`${link.href}/`);
                return (
                  <Link
                    key={link.href}
                    href={link.href}
                    aria-current={isActive ? 'page' : undefined}
                    className={cn(
                      'flex items-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
                      navFocus,
                      isActive
                        ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                        : 'text-sidebar-muted hover:bg-sidebar-accent/60 hover:text-sidebar-accent-foreground'
                    )}
                  >
                    <Icon className="h-4 w-4" aria-hidden="true" />
                    {link.name}
                  </Link>
                );
              })}
            </nav>
          </div>

          {/* Account menu */}
          <div className="flex items-center gap-1">
            {user?.email && (
              <NotificationBell email={user.email}
                className={cn('text-sidebar-muted hover:bg-sidebar-accent hover:text-sidebar-accent-foreground data-[state=open]:bg-sidebar-accent', navFocus)} />
            )}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  className={cn(
                    'h-10 gap-2.5 px-2 text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground data-[state=open]:bg-sidebar-accent',
                    navFocus
                  )}
                >
                  <span className="sr-only">Account menu</span>
                  <span className="flex h-7 w-7 items-center justify-center rounded-md bg-sidebar-accent text-xs font-semibold text-sidebar-accent-foreground">
                    {user?.name ? user.name[0].toUpperCase() : <UserIcon className="h-3.5 w-3.5" aria-hidden="true" />}
                  </span>
                  <span className="hidden text-left sm:block">
                    <span className="block text-xs font-semibold leading-none">{user?.name || 'My account'}</span>
                    <span className="mt-0.5 block max-w-[140px] truncate text-[11px] leading-tight text-sidebar-muted">
                      {user?.email || 'user@example.com'}
                    </span>
                  </span>
                  <ChevronDown className="hidden h-3.5 w-3.5 sm:block" aria-hidden="true" />
                </Button>
              </DropdownMenuTrigger>

              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel className="font-normal">
                  <span className="block text-xs text-muted-foreground">Signed in as</span>
                  <span className="mt-0.5 block truncate text-sm font-semibold">
                    {user?.email || 'user@example.com'}
                  </span>
                  {user?.role && (
                    <Badge variant="secondary" className="mt-1.5 uppercase tracking-wide">
                      {user.role}
                    </Badge>
                  )}
                </DropdownMenuLabel>

                {/* Mobile-only: primary links live in the menu below md */}
                <div className="md:hidden">
                  <DropdownMenuSeparator />
                  {navLinks.map((link) => (
                    <DropdownMenuItem key={link.href} asChild>
                      <Link href={link.href}>{link.name}</Link>
                    </DropdownMenuItem>
                  ))}
                </div>

                <DropdownMenuSeparator />
                <DropdownMenuItem
                  onSelect={handleLogout}
                  className="text-destructive focus:bg-destructive/10 focus:text-destructive"
                >
                  <LogOut aria-hidden="true" />
                  Sign out
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>

            <Button
              variant="ghost"
              size="icon"
              onClick={handleLogout}
              aria-label="Sign out"
              title="Sign out"
              className={cn('h-10 w-10 text-sidebar-muted hover:bg-sidebar-accent hover:text-sidebar-accent-foreground', navFocus)}
            >
              <LogOut aria-hidden="true" />
            </Button>
          </div>
        </div>
      </div>
    </header>
  );
}
