'use client';

import React, { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { Bell } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import { NOTIFICATIONS_CHANGED, notificationHref, notificationService, type NotificationItem } from './notification-service';
import { relativeTime } from './notification-time';
import { desktopPermission, shouldShowDesktop, showDesktop } from './desktop';

/** Without the real-time stream the count is polled every minute; with it, only every five minutes as a safety net. */
const POLL_MS = 60_000;
const POLL_STREAMING_MS = 300_000;

/**
 * The top bar's bell: how many notifications are unread, and the latest five. New notifications arrive in real time
 * (P4's notification stream): the count updates at once, a screen reader hears the title, and a desktop notification is
 * shown if the person allowed it and is not looking at the app. The count is also polled as a fallback and refreshed
 * whenever the menu closes; opening the menu loads the latest. Errors keep the last known state quietly.
 */
export function NotificationBell({ email, className }: { email: string; className?: string }) {
  const [unread, setUnread] = useState(0);
  const [latest, setLatest] = useState<NotificationItem[]>([]);
  const [open, setOpen] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const seq = useRef(0);

  const refreshCount = useCallback(() => {
    notificationService.unreadCount(email).then(setUnread, () => undefined);
  }, [email]);

  useEffect(() => {
    refreshCount();
    const id = setInterval(refreshCount, streaming ? POLL_STREAMING_MS : POLL_MS);
    window.addEventListener(NOTIFICATIONS_CHANGED, refreshCount);
    return () => { clearInterval(id); window.removeEventListener(NOTIFICATIONS_CHANGED, refreshCount); };
  }, [refreshCount, streaming]);

  useEffect(() => notificationService.stream({
    onReady: (count) => { setStreaming(true); setUnread(count); },
    onDisconnect: () => setStreaming(false),
    onNotification: (n) => {
      setUnread(n.unreadCount);
      setAnnouncement(`New notification: ${n.title}`);
      if (shouldShowDesktop(desktopPermission(), document.visibilityState)) {
        const href = n.incidentId ? `/incidents/${encodeURIComponent(n.incidentId)}` : null;
        showDesktop(n.title, n.body, href, `itsm-${seq.current++}`).catch(() => undefined);
      }
    },
  }), []);

  useEffect(() => {
    if (open) notificationService.list(email, false, 1, 5).then((p) => setLatest(p.items), () => undefined);
    else refreshCount();
  }, [open, email, refreshCount]);

  const markAll = async () => {
    await notificationService.markAllRead(email).catch(() => undefined);
    setLatest((l) => l.map((n) => (n.readAt ? n : { ...n, readAt: new Date().toISOString() })));
    setUnread(0);
  };

  const label = unread === 0 ? 'Notifications, none unread' : `Notifications, ${unread} unread`;

  return (
    <>
    <span role="status" aria-live="polite" className="sr-only" data-testid="notification-announcer">{announcement}</span>
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={label} title={label} data-testid="notification-bell" className={cn('relative h-10 w-10', className)}>
          <Bell aria-hidden="true" />
          {unread > 0 && (
            <span aria-hidden="true" className="absolute right-1.5 top-1.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold leading-none text-white">
              {unread > 99 ? '99+' : unread}
            </span>
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80">
        <DropdownMenuLabel className="flex items-center justify-between gap-2">
          <span>Notifications</span>
          {unread > 0 && (
            <button type="button" onClick={markAll} className="rounded text-xs font-medium text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
              Mark all as read
            </button>
          )}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {latest.length === 0 ? (
          <p className="px-2 py-3 text-sm text-muted-foreground">You have no notifications.</p>
        ) : (
          latest.map((n) => {
            const href = notificationHref(n);
            const content = (
              <span className="block min-w-0">
                <span className="flex items-center gap-1.5">
                  {!n.readAt && <span className="h-2 w-2 shrink-0 rounded-full bg-primary" aria-hidden="true" />}
                  <span className={cn('truncate text-sm', !n.readAt && 'font-semibold')}>{n.title}</span>
                </span>
                <span className="block text-xs text-muted-foreground">{n.readAt ? '' : 'Unread · '}{relativeTime(n.createdAt, Date.now())}</span>
              </span>
            );
            return (
              <DropdownMenuItem key={n.id} asChild={!!href} onSelect={() => { if (!n.readAt) notificationService.markRead(email, n.id).catch(() => undefined); }}>
                {href ? <Link href={href}>{content}</Link> : content}
              </DropdownMenuItem>
            );
          })
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <Link href="/notifications" className="justify-center text-sm font-medium">See all notifications</Link>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
    </>
  );
}
