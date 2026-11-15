'use client';

import React from 'react';
import Link from 'next/link';
import { AlertTriangle, ArrowRight, CheckCircle2, Clock, Inbox, Plus } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { ErrorState } from '@/features/admin/components/states';
import { useResource } from '@/features/admin/hooks/use-resource';
import { analyticsApi, presetRange } from '@/features/admin/services/analytics-api';
import { pct } from '@/features/admin/analytics/format';
import { PriorityBadge, StateBadge } from '@/features/incidents/components/badges';
import { SlaQueueCard } from '@/features/incidents/components/sla-queue-card';
import { useActor } from '@/features/incidents/components/workspace-shell';
import { listDeadline } from '@/features/incidents/model/sla';
import type { Actor, Audience } from '@/features/incidents/model/types';
import { audienceFor, incidentService, type IncidentRow, type SlaAttention } from '@/features/incidents/services/incident-service';
import { notificationService } from '@/features/notifications/notification-service';
import { cn } from '@/lib/utils';

/**
 * The landing page after sign-in: one view per role, each answering "what needs me now?" first and "how are things going?"
 * second. Every figure comes from the same APIs the incident list and analytics pages use, so the numbers always agree.
 */
export default function DashboardPage() {
  const actor = useActor();
  const first = actor.name.split(' ')[0] || actor.name;
  const subtitle: Record<Actor['role'], string> = {
    EMPLOYEE: 'Problems you have reported and anything waiting on you.',
    TECHNICIAN: 'Your queue, your workload and the deadlines closest to breaching.',
    ADMIN: 'Service health across every incident, and what needs a decision.',
  };
  return (
    <div className="space-y-8">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight text-foreground">Welcome back, {first}</h1>
          <p className="mt-1 text-sm text-muted-foreground">{subtitle[actor.role]}</p>
        </div>
        <Button asChild>
          <Link href="/incidents/new"><Plus className="mr-1.5 h-4 w-4" aria-hidden="true" />Report incident</Link>
        </Button>
      </div>
      {actor.role === 'EMPLOYEE' && <EmployeeDashboard actor={actor} />}
      {actor.role === 'TECHNICIAN' && <TechnicianDashboard actor={actor} />}
      {actor.role === 'ADMIN' && <AdminDashboard actor={actor} />}
    </div>
  );
}

// ---- Employee ------------------------------------------------------------------------------------------------------

function EmployeeDashboard({ actor }: { actor: Actor }) {
  const audience = audienceFor(actor);
  const open = useResource(() => incidentService.list(actor, 'OPEN'), [actor.email]);
  const unread = useResource(() => notificationService.unreadCount(actor.email).catch(() => null), [actor.email]);
  if (open.error) return <Card><ErrorState message={open.error} onRetry={open.reload} /></Card>;
  const d = open.data;
  const waiting = d?.items.filter((i) => i.needsAttention) ?? [];
  const confirm = waiting.filter((i) => i.state === 'RESOLVED').length;
  return (
    <>
      <StatRow loading={!d} stats={d ? [
        { label: 'Open', value: d.counts.OPEN ?? 0, hint: 'Reported and not yet confirmed fixed', href: '/incidents' },
        { label: 'Waiting on you', value: waiting.length, hint: waiting.length ? 'A question or a fix to confirm' : 'Nothing right now', tone: waiting.length ? 'warn' : undefined },
        { label: 'Fixes to confirm', value: confirm, hint: 'Check and tell us if it works' },
        { label: 'Resolved', value: d.counts.DONE ?? 0, hint: 'Confirmed fixed or closed' },
        { label: 'Unread notifications', value: unread.data ?? '—', hint: 'Updates on your incidents', href: '/notifications' },
      ] : []} />
      <div className="grid gap-6 lg:grid-cols-2 [&>*]:min-w-0">
        <IncidentListCard title="Waiting on you" description="Answer a question or confirm a fix so work can continue." rows={waiting} audience={audience}
          loading={!d} empty="Nothing is waiting on you." emptyIcon={CheckCircle2} />
        <IncidentListCard title="Your open incidents" description="Most urgent first." rows={d?.items.slice(0, 6) ?? []} audience={audience}
          loading={!d} empty="You have no open incidents." more={(d?.counts.OPEN ?? 0) > 6 ? '/incidents' : undefined} showAssignee />
      </div>
    </>
  );
}

// ---- Technician ----------------------------------------------------------------------------------------------------

function TechnicianDashboard({ actor }: { actor: Actor }) {
  const audience = audienceFor(actor);
  const active = useResource(() => incidentService.list(actor, 'ACTIVE', '', 1, {}, 'DEADLINE'), [actor.email]);
  const waiting = useResource(() => incidentService.list(actor, 'WAITING'), [actor.email]);
  const load = useResource(() => incidentService.myWorkload(actor).catch(() => null), [actor.email]);
  if (active.error) return <Card><ErrorState message={active.error} onRetry={active.reload} /></Card>;
  const a = active.data;
  const toAccept = a?.items.filter((i) => i.state === 'ASSIGNED') ?? [];
  return (
    <>
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,2fr)] [&>*]:min-w-0">
        <WorkloadCard loading={load.loading} data={load.data} />
        <SlaAttentionCard loading={!a} attention={a?.attention ?? null} scope="your incidents" />
      </div>
      <StatRow loading={!a} stats={a ? [
        { label: 'Active', value: a.counts.ACTIVE ?? 0, hint: 'Assigned, accepted or in progress', href: '/incidents' },
        { label: 'To accept', value: toAccept.length, hint: toAccept.length ? 'Accept to meet the response target' : 'All accepted', tone: toAccept.length ? 'warn' : undefined },
        { label: 'Waiting on reporter', value: a.counts.WAITING ?? 0, hint: 'Question asked or fix awaiting confirmation' },
        { label: 'Done', value: a.counts.DONE ?? 0, hint: 'Confirmed fixed or closed' },
      ] : []} />
      <div className="grid gap-6 lg:grid-cols-2 [&>*]:min-w-0">
        <SlaQueueCard actor={actor} />
        <IncidentListCard title="Waiting on the reporter" description="Their SLA clock is paused until they answer or confirm." rows={waiting.data?.items.slice(0, 6) ?? []}
          audience={audience} loading={!waiting.data} empty="Nothing is waiting on a reporter." emptyIcon={CheckCircle2} />
      </div>
    </>
  );
}

function WorkloadCard({ loading, data }: { loading: boolean; data: { active: number; maxWorkload: number } | null }) {
  const ratio = data && data.maxWorkload > 0 ? data.active / data.maxWorkload : 0;
  const tone = ratio >= 1 ? 'bg-destructive' : ratio >= 0.75 ? 'bg-warning' : 'bg-primary';
  return (
    <Card>
      <CardHeader className="pb-3"><CardTitle className="text-base">Your workload</CardTitle><CardDescription>Open incidents against your configured ceiling.</CardDescription></CardHeader>
      <CardContent>
        {loading ? <Skeleton className="h-14 w-full" /> : data ? (
          <div className="space-y-3">
            <p className="text-3xl font-semibold tabular-nums">{data.active}<span className="text-base font-normal text-muted-foreground"> of {data.maxWorkload}</span></p>
            <div className="h-2.5 w-full overflow-hidden rounded-full bg-muted" role="meter" aria-valuemin={0} aria-valuemax={data.maxWorkload} aria-valuenow={data.active} aria-label="Workload">
              <div className={cn('h-full rounded-full', tone)} style={{ width: `${Math.min(100, ratio * 100)}%` }} />
            </div>
            <p className="text-xs text-muted-foreground">{ratio >= 1 ? 'At capacity: new incidents go to other technicians.' : `${data.maxWorkload - data.active} more before you reach capacity.`}</p>
          </div>
        ) : <p className="text-sm text-muted-foreground">No technician profile yet. Ask an administrator to create one.</p>}
      </CardContent>
    </Card>
  );
}

// ---- Admin ---------------------------------------------------------------------------------------------------------

function AdminDashboard({ actor }: { actor: Actor }) {
  const audience = audienceFor(actor);
  const range = presetRange(30);
  const unassigned = useResource(() => incidentService.list(actor, 'UNASSIGNED'), [actor.email]);
  const activeList = useResource(() => incidentService.list(actor, 'ACTIVE', '', 1, {}, 'DEADLINE'), [actor.email]);
  const summary = useResource(() => analyticsApi.summary(range.from, range.to), [range.from, range.to]);
  const s = summary.data;
  const criticalOpen = s ? (s.incidents.openByPriority.P1 ?? 0) : 0;
  const breaches = s ? s.sla.overall.responseBreached + s.sla.overall.resolutionBreached : 0;
  return (
    <>
      {summary.error ? <Card><ErrorState message={summary.error} onRetry={summary.reload} /></Card> : (
        <StatRow loading={!s} stats={s ? [
          { label: 'Reported, 30 days', value: s.incidents.reported, hint: `${s.incidents.resolved} resolved in the same days`, href: '/admin/analytics' },
          { label: 'Open now', value: s.incidents.openNow, hint: `${s.incidents.unassignedNow} not assigned`, href: '/incidents' },
          { label: 'Critical open', value: criticalOpen, hint: 'P1 incidents not yet resolved', tone: criticalOpen ? 'bad' : undefined },
          { label: 'SLA violations', value: breaches, hint: `Resolution SLA met: ${pct(s.sla.overall.resolutionCompliancePct)}`, tone: breaches ? 'bad' : undefined },
          { label: 'Escalations', value: s.escalations.total, hint: 'Raised in the last 30 days', href: '/admin/escalation-settings' },
        ] : []} />
      )}
      <div className="grid gap-6 lg:grid-cols-2 [&>*]:min-w-0">
        <SlaAttentionCard loading={!activeList.data} attention={activeList.data?.attention ?? null} scope="incidents in progress" />
        <TechnicianLoadCard loading={!s} rows={s?.technicians ?? []} />
      </div>
      <div className="grid gap-6 lg:grid-cols-2 [&>*]:min-w-0">
        <IncidentListCard title="Awaiting assignment" description="Assign these so their response clock can be met." rows={unassigned.data?.items.slice(0, 6) ?? []}
          audience={audience} loading={!unassigned.data} empty="Every incident has a technician." emptyIcon={CheckCircle2}
          more={(unassigned.data?.counts.UNASSIGNED ?? 0) > 6 ? '/incidents' : undefined} />
        <SlaQueueCard actor={actor} />
      </div>
      <ServiceCard loading={!s} rows={s?.sla.byService ?? []} />
    </>
  );
}

function TechnicianLoadCard({ loading, rows }: { loading: boolean; rows: { userId: number; name: string; maxWorkload: number; activeIncidents: number }[] }) {
  const sorted = [...rows].sort((a, b) => b.activeIncidents / Math.max(1, b.maxWorkload) - a.activeIncidents / Math.max(1, a.maxWorkload));
  return (
    <Card>
      <CardHeader className="pb-3"><CardTitle className="text-base">Technician workload</CardTitle><CardDescription>Open incidents against each technician&apos;s ceiling.</CardDescription></CardHeader>
      <CardContent>
        {loading ? <Skeleton className="h-28 w-full" /> : sorted.length === 0 ? <p className="text-sm text-muted-foreground">No technician profiles yet.</p> : (
          <ul className="space-y-3">
            {sorted.slice(0, 6).map((t) => {
              const r = t.maxWorkload > 0 ? t.activeIncidents / t.maxWorkload : 0;
              return (
                <li key={t.userId} className="space-y-1">
                  <div className="flex items-baseline justify-between gap-3 text-sm"><span className="truncate font-medium">{t.name}</span><span className="tabular-nums text-muted-foreground">{t.activeIncidents} of {t.maxWorkload}</span></div>
                  <div className="h-2 w-full overflow-hidden rounded-full bg-muted"><div className={cn('h-full rounded-full', r >= 1 ? 'bg-destructive' : r >= 0.75 ? 'bg-warning' : 'bg-primary')} style={{ width: `${Math.min(100, r * 100)}%` }} /></div>
                </li>
              );
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function ServiceCard({ loading, rows }: { loading: boolean; rows: { id: string | null; name: string; counts: { tracked: number; resolutionBreached: number; resolutionCompliancePct: number | null } }[] }) {
  const top = [...rows].sort((a, b) => b.counts.tracked - a.counts.tracked).slice(0, 6);
  return (
    <Card>
      <CardHeader className="pb-3"><CardTitle className="text-base">Incidents by service</CardTitle><CardDescription>Last 30 days, busiest first.</CardDescription></CardHeader>
      <CardContent>
        {loading ? <Skeleton className="h-28 w-full" /> : top.length === 0 ? <p className="text-sm text-muted-foreground">No incidents in the last 30 days.</p> : (
          <table className="w-full text-sm">
            <thead><tr className="text-left text-xs text-muted-foreground"><th className="pb-2 font-medium">Service</th><th className="pb-2 text-right font-medium">Incidents</th><th className="pb-2 text-right font-medium">Breached</th><th className="pb-2 text-right font-medium">SLA met</th></tr></thead>
            <tbody>
              {top.map((r) => (
                <tr key={r.id ?? r.name} className="border-t">
                  <td className="py-2 pr-2 font-medium">{r.name}</td>
                  <td className="py-2 text-right tabular-nums">{r.counts.tracked}</td>
                  <td className={cn('py-2 text-right tabular-nums', r.counts.resolutionBreached > 0 && 'font-semibold text-destructive')}>{r.counts.resolutionBreached}</td>
                  <td className="py-2 text-right tabular-nums">{pct(r.counts.resolutionCompliancePct)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </CardContent>
    </Card>
  );
}

// ---- Shared pieces -------------------------------------------------------------------------------------------------

interface Stat { label: string; value: number | string; hint: string; href?: string; tone?: 'warn' | 'bad' }

function StatRow({ stats, loading }: { stats: Stat[]; loading: boolean }) {
  if (loading) return <div className="grid grid-cols-2 gap-4 md:grid-cols-5">{[0, 1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-[104px]" />)}</div>;
  return (
    <div className={cn('grid grid-cols-2 gap-4', stats.length >= 5 ? 'md:grid-cols-5' : 'md:grid-cols-4')}>
      {stats.map((s) => {
        const body = (
          <Card className={cn('h-full transition-colors', s.href && 'hover:border-primary/50', s.tone === 'bad' && 'border-destructive/50', s.tone === 'warn' && 'border-warning/60')}>
            <CardContent className="space-y-1 p-4">
              <p className="text-xs font-medium text-muted-foreground">{s.label}</p>
              <p className={cn('text-2xl font-semibold tabular-nums', s.tone === 'bad' && 'text-destructive')}>{s.value}</p>
              <p className="text-xs text-muted-foreground">{s.hint}</p>
            </CardContent>
          </Card>
        );
        return s.href ? <Link key={s.label} href={s.href} className="rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">{body}</Link> : <div key={s.label}>{body}</div>;
      })}
    </div>
  );
}

function SlaAttentionCard({ loading, attention, scope }: { loading: boolean; attention: SlaAttention | null; scope: string }) {
  const items = attention ? [
    { label: 'Breached', value: attention.breached, desc: 'Past the resolution deadline', icon: AlertTriangle, tone: 'text-destructive' },
    { label: 'At risk', value: attention.atRisk, desc: 'Past the warning point', icon: Clock, tone: 'text-warning-foreground' },
    { label: 'Predicted to miss', value: attention.predictedMiss, desc: 'Likely to breach at the current pace', icon: Clock, tone: 'text-muted-foreground' },
  ] : [];
  return (
    <Card>
      <CardHeader className="pb-3"><CardTitle className="text-base">Service level</CardTitle><CardDescription>Across {scope}, checked every minute.</CardDescription></CardHeader>
      <CardContent>
        {loading ? <Skeleton className="h-20 w-full" /> : !attention ? <p className="text-sm text-muted-foreground">SLA tracking is not reporting yet.</p> : (
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
            {items.map((i) => (
              <Link key={i.label} href="/incidents" className={cn('rounded-lg border p-3 transition-colors hover:border-primary/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', i.value > 0 && i.label === 'Breached' && 'border-destructive/50 bg-destructive/5')}>
                <div className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground"><i.icon className={cn('h-3.5 w-3.5', i.value > 0 && i.tone)} aria-hidden="true" />{i.label}</div>
                <p className={cn('mt-1 text-2xl font-semibold tabular-nums', i.value > 0 && i.tone)}>{i.value}</p>
                <p className="text-xs text-muted-foreground">{i.desc}</p>
              </Link>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function IncidentListCard({ title, description, rows, audience, loading, empty, emptyIcon: EmptyIcon = Inbox, more, showDeadline, showAssignee }: {
  title: string; description: string; rows: IncidentRow[]; audience: Audience; loading: boolean; empty: string;
  emptyIcon?: React.ComponentType<{ className?: string }>; more?: string; showDeadline?: boolean; showAssignee?: boolean;
}) {
  const now = Date.now();
  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-3 space-y-0 pb-3">
        <div className="min-w-0"><CardTitle className="text-base">{title}</CardTitle><CardDescription className="mt-1">{description}</CardDescription></div>
        {more && <Link href={more} className="inline-flex shrink-0 items-center gap-1 text-sm text-primary hover:underline">View all<ArrowRight className="h-3.5 w-3.5" aria-hidden="true" /></Link>}
      </CardHeader>
      <CardContent>
        {loading ? <div className="space-y-2">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-12 w-full" />)}</div> : rows.length === 0 ? (
          <div className="flex items-center gap-2 py-6 text-sm text-muted-foreground"><EmptyIcon className="h-4 w-4" aria-hidden="true" />{empty}</div>
        ) : (
          <ul className="divide-y">
            {rows.map((i) => {
              const due = showDeadline ? listDeadline(i.sla, now) : null;
              return (
                <li key={i.id}>
                  <Link href={`/incidents/${encodeURIComponent(i.id)}`} className="flex flex-col gap-1.5 py-3 hover:bg-muted/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:flex-row sm:items-center sm:justify-between sm:gap-3">
                    <div className="min-w-0">
                      <p className="truncate text-sm font-medium text-primary">{i.title}</p>
                      <p className="text-xs text-muted-foreground"><span className="font-mono">{i.id}</span> · {i.service}{showAssignee ? ` · ${i.assigneeName ?? 'Not assigned yet'}` : ''}</p>
                    </div>
                    <div className="flex shrink-0 flex-wrap items-center gap-1.5">
                      <PriorityBadge priority={i.priority} />
                      <StateBadge state={i.state} audience={audience} />
                      {due && <span className="text-xs text-muted-foreground">{due.text}</span>}
                      {due?.badge && <Badge variant={due.badge.variant}>{due.badge.label}</Badge>}
                    </div>
                  </Link>
                </li>
              );
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}
