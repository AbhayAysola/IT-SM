'use client';

import React, { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { Bell, CheckCheck } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState, ErrorState } from '@/features/admin/components/states';
import { useActor } from '@/features/incidents/components/workspace-shell';
import { cn } from '@/lib/utils';
import { notificationHref, notificationService, type NotificationPage } from './notification-service';
import { relativeTime } from './notification-time';

/** Everything the signed-in person has been told about: newest first, unread marked in words, not only by colour. */
export default function NotificationsPage() {
  const actor = useActor();
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [page, setPage] = useState(1);
  const [data, setData] = useState<NotificationPage | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(() => {
    setError(null);
    notificationService.list(actor.email, unreadOnly, page).then(setData, (e: unknown) => setError(e instanceof Error ? e.message : 'Could not load your notifications.'));
  }, [actor.email, unreadOnly, page]);
  useEffect(load, [load]);

  const markRead = async (id: number) => { await notificationService.markRead(actor.email, id).catch(() => undefined); load(); };
  const markAll = async () => { await notificationService.markAllRead(actor.email).catch(() => undefined); load(); };
  const pages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;
  const anyUnread = !!data?.items.some((n) => !n.readAt);

  return (
    <>
      <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Notifications</h1>
          <p className="mt-1 text-sm text-muted-foreground">Assignments, questions, fixes to confirm, SLA warnings and escalations.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <div role="group" aria-label="Show" className="flex gap-1">
            <Button size="sm" variant={unreadOnly ? 'outline' : 'default'} aria-pressed={!unreadOnly} onClick={() => { setUnreadOnly(false); setPage(1); }}>All</Button>
            <Button size="sm" variant={unreadOnly ? 'default' : 'outline'} aria-pressed={unreadOnly} onClick={() => { setUnreadOnly(true); setPage(1); }}>Unread</Button>
          </div>
          <Button size="sm" variant="outline" onClick={markAll} disabled={!anyUnread}><CheckCheck aria-hidden="true" /> Mark all as read</Button>
        </div>
      </div>

      <Card>
        <CardContent className="p-0">
          {error ? <ErrorState message={error} onRetry={load} />
            : !data ? <div role="status" aria-label="Loading notifications" className="space-y-2 p-4"><Skeleton className="h-14" /><Skeleton className="h-14" /></div>
            : data.items.length === 0 ? <EmptyState icon={Bell} title={unreadOnly ? 'Nothing unread' : 'No notifications yet'} description="You will be told here when something needs you." />
            : (
              <ul aria-label="Notifications" className="divide-y">
                {data.items.map((n) => {
                  const href = notificationHref(n);
                  return (
                    <li key={n.id} className={cn('flex flex-wrap items-start gap-3 p-4', !n.readAt && 'bg-primary/5')}>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          {!n.readAt && <Badge variant="default">Unread</Badge>}
                          {n.priority && <Badge variant="outline">{n.priority}</Badge>}
                          <span className="text-xs text-muted-foreground">{relativeTime(n.createdAt, Date.now())}</span>
                        </div>
                        <p className={cn('mt-1 break-words text-sm', !n.readAt && 'font-semibold')}>
                          {href ? <Link href={href} onClick={() => { if (!n.readAt) notificationService.markRead(actor.email, n.id).catch(() => undefined); }} className="underline-offset-2 hover:underline">{n.title}</Link> : n.title}
                        </p>
                        <p className="mt-0.5 break-words text-sm text-muted-foreground">{n.body}</p>
                      </div>
                      {!n.readAt && <Button size="sm" variant="ghost" onClick={() => markRead(n.id)}>Mark as read</Button>}
                    </li>
                  );
                })}
              </ul>
            )}
        </CardContent>
      </Card>

      {data && pages > 1 && (
        <nav aria-label="Notification pages" className="mt-4 flex items-center justify-end gap-2 text-sm">
          <Button size="sm" variant="outline" disabled={page <= 1} onClick={() => setPage(page - 1)}>Previous</Button>
          <span>Page {page} of {pages}</span>
          <Button size="sm" variant="outline" disabled={page >= pages} onClick={() => setPage(page + 1)}>Next</Button>
        </nav>
      )}
    </>
  );
}
