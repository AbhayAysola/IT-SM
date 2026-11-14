'use client';

import React, { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';
import { PageHeader } from '../components/page-header';
import { ErrorState } from '../components/states';
import { analyticsApi, presetRange, validateRange } from '../services/analytics-api';
import { ServiceError, type AnalyticsSummary, type SlaCounts } from '../types';
import { ColumnChart, MeterList, RankedBars, StatTile } from './charts';
import { compact, formatMinutes, pct, shortDay, TRIGGER_LABEL } from './format';

const PRESETS = [7, 30, 90] as const;
const metDetail = (c: SlaCounts) => `${c.resolutionMet} met · ${c.resolutionBreached} breached`;

/**
 * P4's analytics dashboard (docs/architecture/p4.md section 5). One filter row (the date range) scopes everything below
 * it; while a new range loads, the previous numbers stay on screen, dimmed, instead of a skeleton flash.
 */
export default function AnalyticsPage() {
  const [range, setRange] = useState(() => presetRange(30));
  const [preset, setPreset] = useState<number | null>(30);
  const [custom, setCustom] = useState(range);
  const [rangeError, setRangeError] = useState<string | null>(null);
  const [data, setData] = useState<AnalyticsSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tables, setTables] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
    setLoading(true); setError(null);
    analyticsApi.summary(range.from, range.to)
      .then((d) => { if (live) setData(d); })
      .catch((e: unknown) => { if (live) setError(e instanceof ServiceError || e instanceof Error ? e.message : 'Could not load analytics.'); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [range, attempt]);

  const choosePreset = (days: number) => { const r = presetRange(days); setPreset(days); setCustom(r); setRangeError(null); setRange(r); };
  const applyCustom = (e: React.FormEvent) => {
    e.preventDefault();
    const err = validateRange(custom.from, custom.to);
    setRangeError(err);
    if (!err) { setPreset(null); setRange({ ...custom }); }
  };

  return (
    <>
      <PageHeader title="Analytics" description="Service levels, response and resolution times, escalations and technician workload. Figures cover incidents reported in the chosen days (UTC); open counts are as of now." />

      <form onSubmit={applyCustom} className="mb-6 flex flex-wrap items-end gap-3" aria-label="Date range">
        <div role="group" aria-label="Preset ranges" className="flex gap-1">
          {PRESETS.map((d) => (
            <Button key={d} type="button" size="sm" variant={preset === d ? 'default' : 'outline'} aria-pressed={preset === d} onClick={() => choosePreset(d)}>
              Last {d} days
            </Button>
          ))}
        </div>
        <div className="space-y-1">
          <Label htmlFor="an-from" className="text-xs">From</Label>
          <Input id="an-from" type="date" className="h-9 w-40" value={custom.from} aria-invalid={!!rangeError} aria-describedby={rangeError ? 'an-range-error' : undefined}
            onChange={(e) => setCustom({ ...custom, from: e.target.value })} />
        </div>
        <div className="space-y-1">
          <Label htmlFor="an-to" className="text-xs">To</Label>
          <Input id="an-to" type="date" className="h-9 w-40" value={custom.to} aria-invalid={!!rangeError} aria-describedby={rangeError ? 'an-range-error' : undefined}
            onChange={(e) => setCustom({ ...custom, to: e.target.value })} />
        </div>
        <Button type="submit" size="sm" variant="outline">Apply</Button>
        <Button type="button" size="sm" variant="ghost" aria-pressed={tables} onClick={() => setTables((v) => !v)}>{tables ? 'Show charts' : 'Show as tables'}</Button>
        {rangeError && <p id="an-range-error" role="alert" className="w-full text-sm font-medium text-destructive">{rangeError}</p>}
      </form>

      {error && !data && <Card><ErrorState message={error} onRetry={() => setAttempt((n) => n + 1)} /></Card>}
      {!data && !error && (
        <div role="status" aria-label="Loading analytics" className="space-y-4"><Skeleton className="h-24" /><Skeleton className="h-64" /></div>
      )}

      {data && (
        <div className={cn('space-y-6 transition-opacity', loading && 'opacity-60')} aria-busy={loading} data-testid="analytics">
          {error && <p role="alert" className="text-sm font-medium text-destructive">{error} The figures below are from the previous range.</p>}
          <p className="text-sm text-muted-foreground">{shortDay(data.from)} to {shortDay(data.to)}</p>

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
            <StatTile testId="kpi-reported" label="Reported" value={compact(data.incidents.reported)} />
            <StatTile testId="kpi-resolved" label="Resolved" value={compact(data.incidents.resolved)} />
            <StatTile testId="kpi-open" label="Open now" value={compact(data.incidents.openNow)} sub={`${compact(data.incidents.unassignedNow)} not assigned`} />
            <StatTile testId="kpi-sla" label="Resolution SLA met" value={pct(data.sla.overall.resolutionCompliancePct)} sub={metDetail(data.sla.overall)} />
            <StatTile testId="kpi-escalations" label="Escalations" value={compact(data.escalations.total)} />
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader><CardTitle className="text-base">Every day</CardTitle><CardDescription>Incidents reported and SLA breaches per day.</CardDescription></CardHeader>
              <CardContent>
                {tables ? <DailyTable data={data} /> : (
                  <div className="grid gap-6 sm:grid-cols-2">
                    <ColumnChart title="Reported" valueLabel="reported" points={data.perDay.map((d) => ({ date: d.date, value: d.reported }))} />
                    <ColumnChart title="SLA breaches" valueLabel="breaches" points={data.perDay.map((d) => ({ date: d.date, value: d.breaches }))} />
                  </div>
                )}
              </CardContent>
            </Card>

            <Card>
              <CardHeader><CardTitle className="text-base">Time taken</CardTitle><CardDescription>From report to first response and to resolution (elapsed time, pauses included).</CardDescription></CardHeader>
              <CardContent className="grid gap-3 sm:grid-cols-2">
                <StatTile label="To respond, on average" value={formatMinutes(data.times.toRespond.meanMinutes)} sub={`90% within ${formatMinutes(data.times.toRespond.p90Minutes)} · ${compact(data.times.toRespond.count)} incidents`} />
                <StatTile label="To resolve, on average" value={formatMinutes(data.times.toResolve.meanMinutes)} sub={`90% within ${formatMinutes(data.times.toResolve.p90Minutes)} · ${compact(data.times.toResolve.count)} incidents`} />
              </CardContent>
            </Card>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader><CardTitle className="text-base">Resolution SLA met, by priority</CardTitle><CardDescription>Share of decided resolution clocks that were met.</CardDescription></CardHeader>
              <CardContent>
                <MeterList empty="No SLA was decided in these days."
                  rows={data.sla.byPriority.map((g) => ({ name: g.name, value: g.counts.resolutionCompliancePct, detail: metDetail(g.counts) }))} />
                <p className="mt-4 text-xs text-muted-foreground">Response SLA met overall: <span className="font-semibold text-foreground">{pct(data.sla.overall.responseCompliancePct)}</span> ({data.sla.overall.responseMet} met · {data.sla.overall.responseBreached} breached)</p>
              </CardContent>
            </Card>
            <Card>
              <CardHeader><CardTitle className="text-base">Escalations, by reason</CardTitle></CardHeader>
              <CardContent>
                {tables ? (
                  <SimpleTable caption="Escalations by reason" head={['Reason', 'Escalations']}
                    rows={Object.entries(data.escalations.byTrigger).map(([k, v]) => [TRIGGER_LABEL[k] ?? k, compact(v)])} />
                ) : (
                  <RankedBars empty="Nothing was escalated in these days." rows={Object.entries(data.escalations.byTrigger).map(([k, v]) => ({ name: TRIGGER_LABEL[k] ?? k, value: v }))} />
                )}
              </CardContent>
            </Card>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <SlaTableCard title="By service" groups={data.sla.byService} />
            <SlaTableCard title="By category" groups={data.sla.byCategory} />
          </div>

          <Card>
            <CardHeader><CardTitle className="text-base">Technicians</CardTitle><CardDescription>Open incidents now, and what they resolved and how it was rated in these days.</CardDescription></CardHeader>
            <CardContent>
              <SimpleTable caption="Technicians" head={['Technician', 'Open now', 'Resolved', 'Average rating']}
                rows={data.technicians.map((t) => [t.name, `${t.activeIncidents} of ${t.maxWorkload}`, compact(t.resolved), t.averageRating === null ? '—' : `${t.averageRating.toFixed(1)} of 5 (${t.ratings})`])}
                empty="No active technicians." />
            </CardContent>
          </Card>
        </div>
      )}
    </>
  );
}

function SlaTableCard({ title, groups }: { title: string; groups: AnalyticsSummary['sla']['byService'] }) {
  return (
    <Card>
      <CardHeader><CardTitle className="text-base">SLA {title.toLowerCase()}</CardTitle></CardHeader>
      <CardContent>
        <SimpleTable caption={`SLA ${title.toLowerCase()}`} head={[title.replace('By ', '').replace(/^./, (c) => c.toUpperCase()), 'Tracked', 'Response met', 'Resolution met']}
          rows={groups.map((g) => [g.name, compact(g.counts.tracked), pct(g.counts.responseCompliancePct), pct(g.counts.resolutionCompliancePct)])}
          empty="No SLA was tracked in these days." />
      </CardContent>
    </Card>
  );
}

function DailyTable({ data }: { data: AnalyticsSummary }) {
  return (
    <SimpleTable caption="Every day" head={['Day', 'Reported', 'Resolved', 'SLA breaches', 'Escalations']}
      rows={data.perDay.map((d) => [shortDay(d.date), compact(d.reported), compact(d.resolved), compact(d.breaches), compact(d.escalations)])} />
  );
}

function SimpleTable({ caption, head, rows, empty }: { caption: string; head: string[]; rows: string[][]; empty?: string }) {
  if (rows.length === 0 && empty) return <p className="text-sm text-muted-foreground">{empty}</p>;
  return (
    <div className="max-h-80 overflow-auto">
      <table className="w-full text-sm">
        <caption className="sr-only">{caption}</caption>
        <thead className="sticky top-0 bg-card text-left text-xs text-muted-foreground">
          <tr>{head.map((h, i) => <th key={h} scope="col" className={cn('py-2 font-medium', i > 0 && 'text-right')}>{h}</th>)}</tr>
        </thead>
        <tbody className="divide-y">
          {rows.map((r, ri) => (
            <tr key={`${r[0]}-${ri}`}>{r.map((c, i) => <td key={i} className={cn('py-2', i > 0 && 'text-right tabular-nums', i === 0 && 'font-medium')}>{c}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
