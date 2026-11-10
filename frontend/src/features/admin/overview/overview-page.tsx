'use client';

import React from 'react';
import Link from 'next/link';
import { ArrowRight, CheckCircle2, UserCog, UserPlus, Users } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { Badge } from '@/components/ui/badge';
import { PageHeader } from '../components/page-header';
import { ErrorState } from '../components/states';
import { ROLE_LABEL, formatDateTime } from '../components/status-badges';
import { useResource } from '../hooks/use-resource';
import { auditService } from '../services/audit-service';
import { overviewService } from '../services/overview-service';

function Kpi({ label, value, note }: { label: string; value: React.ReactNode; note?: string }) {
  return (
    <Card>
      <CardContent className="p-5">
        <p className="text-sm font-medium text-muted-foreground">{label}</p>
        <p className="mt-1 text-3xl font-semibold tracking-tight">{value}</p>
        {note && <p className="mt-1 text-xs text-muted-foreground">{note}</p>}
      </CardContent>
    </Card>
  );
}

export default function OverviewPage() {
  const overview = useResource(() => overviewService.get(), []);
  const activity = useResource(() => auditService.recent(6), []);
  const o = overview.data;

  return (
    <>
      <PageHeader
        title="Admin overview"
        description="People, technician capacity and anything that needs a decision."
        actions={
          <>
            <Button asChild variant="outline"><Link href="/admin/technicians"><UserCog aria-hidden="true" /> Technicians</Link></Button>
            <Button asChild><Link href="/admin/users"><UserPlus aria-hidden="true" /> Manage users</Link></Button>
          </>
        }
      />

      {overview.error ? (
        <Card><ErrorState message={overview.error} onRetry={overview.reload} /></Card>
      ) : !o ? (
        <div role="status" aria-label="Loading overview" className="space-y-6">
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">{[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-[104px]" />)}</div>
          <div className="grid gap-6 lg:grid-cols-2"><Skeleton className="h-64" /><Skeleton className="h-64" /></div>
        </div>
      ) : (
        <div className="space-y-6">
          <section aria-label="Key figures" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Kpi label="Total users" value={o.totalUsers} note={`${o.activeUsers} active`} />
            <Kpi label="Pending invitations" value={o.invitedUsers} note={`${o.suspendedUsers} suspended`} />
            <Kpi label="Technicians available" value={`${o.technicians.available} / ${o.technicians.total}`} note={`${o.technicians.overCapacity} at full workload`} />
            <Kpi label="Technician utilisation" value={`${o.technicians.utilisationPct}%`} note="Active incidents vs. capacity" />
          </section>

          <div className="grid gap-6 lg:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle className="text-lg">Needs attention</CardTitle>
                <CardDescription>Open the item to see the people affected.</CardDescription>
              </CardHeader>
              <CardContent>
                {o.attention.length === 0 ? (
                  <p className="flex items-center gap-2 text-sm text-muted-foreground" data-testid="all-clear">
                    <CheckCircle2 className="h-4 w-4 text-success" aria-hidden="true" /> Nothing needs attention right now.
                  </p>
                ) : (
                  <ul className="divide-y rounded-md border">
                    {o.attention.map((a) => (
                      <li key={a.id}>
                        <Link href={a.href} className="flex items-center gap-3 px-4 py-3 hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring">
                          <div className="min-w-0 flex-1">
                            <p className="text-sm font-medium">{a.label}</p>
                            <p className="text-xs text-muted-foreground">{a.detail}</p>
                          </div>
                          <ArrowRight className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                        </Link>
                      </li>
                    ))}
                  </ul>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle className="text-lg">People by role</CardTitle>
                <CardDescription>All accounts, any status.</CardDescription>
              </CardHeader>
              <CardContent>
                <ul className="space-y-3">
                  {o.byRole.map((r) => (
                    <li key={r.role}>
                      <Link href={`/admin/users?role=${r.role}`} className="group block rounded-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                        <div className="flex items-baseline justify-between text-sm">
                          <span className="font-medium group-hover:underline">{ROLE_LABEL[r.role]}s</span>
                          <span className="text-muted-foreground">{r.count}</span>
                        </div>
                        <div className="mt-1 h-2 overflow-hidden rounded-sm bg-muted" aria-hidden="true">
                          <div className="h-full bg-primary" style={{ width: `${(r.count / o.totalUsers) * 100}%` }} />
                        </div>
                      </Link>
                    </li>
                  ))}
                </ul>
              </CardContent>
            </Card>
          </div>

          <Card data-testid="recent-changes-card">
            <CardHeader>
              <div className="flex flex-wrap items-center gap-2">
                <CardTitle className="text-lg">Recent changes</CardTitle>
                <Badge variant="secondary" data-testid="demo-data-badge">Demo data</Badge>
              </div>
              <CardDescription data-testid="demo-data-note">
                Changes made in this browser&apos;s local demo data only. This is not the audit history: the server&apos;s
                persisted audit events are in Audit logs.
              </CardDescription>
            </CardHeader>
            <CardContent>
              {activity.error ? (
                <ErrorState message={activity.error} onRetry={activity.reload} />
              ) : !activity.data ? (
                <div role="status" aria-label="Loading recent changes" className="space-y-2">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-10" />)}</div>
              ) : activity.data.length === 0 ? (
                <p className="text-sm text-muted-foreground">No changes recorded yet.</p>
              ) : (
                <ul className="divide-y" data-testid="recent-changes">
                  {activity.data.map((a) => (
                    <li key={a.id} className="flex flex-col gap-1 py-3 sm:flex-row sm:items-center sm:gap-4">
                      <time dateTime={a.at} className="w-40 shrink-0 text-xs text-muted-foreground">{formatDateTime(a.at)}</time>
                      <p className="min-w-0 flex-1 text-sm">{a.summary}</p>
                      <Badge variant="outline" className="w-fit">{a.actor}</Badge>
                    </li>
                  ))}
                </ul>
              )}
              <Link href="/admin/audit-logs" className="mt-3 inline-flex items-center gap-1 text-sm font-medium text-primary underline-offset-4 hover:underline">
                Open the audit logs (persisted events) <ArrowRight className="h-4 w-4" aria-hidden="true" />
              </Link>
            </CardContent>
          </Card>
        </div>
      )}
    </>
  );
}
