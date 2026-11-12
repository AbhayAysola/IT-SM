'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { ArrowLeft, ChevronDown, LogOut, ShieldCheck } from 'lucide-react';
import AuthGuard from '@/components/AuthGuard';
import { getCachedUser } from '@/lib/auth';
import { endSession } from '@/lib/session';
import type { User } from '@/types';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import {
  Sidebar, SidebarContent, SidebarFooter, SidebarGroup, SidebarGroupContent, SidebarGroupLabel, SidebarHeader,
  SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarProvider, SidebarRail, SidebarTrigger,
  useSidebar,
} from '@/components/ui/sidebar';
import { ADMIN_NAV } from '../nav';
import { ToastProvider } from './toast';

function NavLinks() {
  const pathname = usePathname();
  const { setOpenMobile } = useSidebar();
  return (
    <>
      {ADMIN_NAV.map((group) => (
        <SidebarGroup key={group.label}>
          <SidebarGroupLabel>{group.label}</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {group.items.map((item) => {
                const active = item.href === '/admin' ? pathname === '/admin' : pathname.startsWith(item.href);
                return (
                  <SidebarMenuItem key={item.href}>
                    <SidebarMenuButton asChild isActive={active} tooltip={item.title}>
                      <Link href={item.href} aria-current={active ? 'page' : undefined} onClick={() => setOpenMobile(false)}>
                        <item.icon aria-hidden="true" />
                        <span>{item.title}</span>
                      </Link>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                );
              })}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      ))}
    </>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<User | null>(null);
  useEffect(() => setUser(getCachedUser()), []);

  const signOut = async () => {
    await endSession();
    router.replace('/');
  };

  return (
    <>
      <Sidebar collapsible="offcanvas">
        <SidebarHeader className="border-b border-sidebar-border">
          <Link href="/admin" className="flex h-10 items-center gap-2.5 rounded-md px-2 text-base font-semibold tracking-tight text-white">
            <span className="flex h-8 w-8 items-center justify-center rounded-md bg-sidebar-primary text-sidebar-primary-foreground">
              <ShieldCheck className="h-5 w-5" aria-hidden="true" />
            </span>
            IT-SM
            <span className="ml-auto rounded bg-sidebar-accent px-1.5 py-0.5 text-[11px] font-medium text-sidebar-muted">Admin</span>
          </Link>
        </SidebarHeader>
        <SidebarContent>
          <nav aria-label="Admin sections">
            <NavLinks />
          </nav>
        </SidebarContent>
        <SidebarFooter className="border-t border-sidebar-border">
          <SidebarMenu>
            <SidebarMenuItem>
              <SidebarMenuButton asChild tooltip="My dashboard">
                <Link href="/dashboard">
                  <ArrowLeft aria-hidden="true" />
                  <span>My dashboard</span>
                </Link>
              </SidebarMenuButton>
            </SidebarMenuItem>
          </SidebarMenu>
        </SidebarFooter>
        <SidebarRail />
      </Sidebar>

      <div className="flex min-h-svh min-w-0 flex-1 flex-col bg-background">
        <header className="sticky top-0 z-30 flex h-14 items-center gap-2 border-b bg-card px-4">
          <SidebarTrigger aria-label="Toggle navigation" className="-ml-1" />
          <div className="ml-auto">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" className="h-10 gap-2.5 px-2">
                  <span className="sr-only">Account menu</span>
                  <span className="flex h-7 w-7 items-center justify-center rounded-md bg-primary/10 text-xs font-semibold text-primary">
                    {user?.name ? user.name[0].toUpperCase() : 'A'}
                  </span>
                  <span className="hidden text-sm font-medium sm:block">{user?.name || 'Administrator'}</span>
                  <ChevronDown className="hidden h-3.5 w-3.5 sm:block" aria-hidden="true" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel className="font-normal">
                  <span className="block text-xs text-muted-foreground">Signed in as</span>
                  <span className="mt-0.5 block truncate text-sm font-semibold">{user?.email || 'administrator'}</span>
                </DropdownMenuLabel>
                <DropdownMenuSeparator />
                <DropdownMenuItem asChild>
                  <Link href="/dashboard">My dashboard</Link>
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={signOut} className="text-destructive focus:bg-destructive/10 focus:text-destructive">
                  <LogOut aria-hidden="true" /> Sign out
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </header>
        <main id="main" className="mx-auto w-full max-w-7xl flex-1 px-4 py-6 sm:px-6 lg:px-8">{children}</main>
      </div>
    </>
  );
}

export function AdminShell({ children }: { children: React.ReactNode }) {
  return (
    <AuthGuard requiredRole="ADMIN">
      <ToastProvider>
        <SidebarProvider>
          <Shell>{children}</Shell>
        </SidebarProvider>
      </ToastProvider>
    </AuthGuard>
  );
}
