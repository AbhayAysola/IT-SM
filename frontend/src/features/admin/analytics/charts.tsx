'use client';

import React, { useState } from 'react';
import { cn } from '@/lib/utils';
import { compact, niceMax, pct, shortDay } from './format';

/*
 * The dashboard's marks, built to the dataviz rules: one hue (the theme accent, validated against the card surface),
 * bars at most 24px thick with a 4px rounded data end and a square baseline, a 2px surface gap between adjacent
 * columns, hairline gridlines, text in text tokens (never the series colour), a tooltip on hover AND keyboard focus that
 * never gates (every value is also in the table view), and no legend for a single series (the title names it).
 */

/** A headline number: label, value, optional context line. */
export function StatTile({ label, value, sub, testId }: { label: string; value: string; sub?: string; testId?: string }) {
  return (
    <div className="rounded-lg border bg-card p-4" data-testid={testId}>
      <p className="text-sm text-muted-foreground">{label}</p>
      <p className="mt-1 text-3xl font-semibold tracking-tight">{value}</p>
      {sub && <p className="mt-1 text-xs text-muted-foreground">{sub}</p>}
    </div>
  );
}

/**
 * Columns over time for ONE series. Each column's hit area is its whole slot (not just the painted bar) and is focusable
 * through a roving pointer: the chart is one tab stop, arrow keys move between days.
 */
export function ColumnChart({ title, points, valueLabel }: { title: string; points: { date: string; value: number }[]; valueLabel: string }) {
  const [active, setActive] = useState<number | null>(null);
  const max = niceMax(Math.max(0, ...points.map((p) => p.value)));
  // Counts are whole numbers: a midline only where it lands on one (never "0.5 breaches").
  const ticks = Number.isInteger(max / 2) ? [max, max / 2, 0] : [max, 0];
  const shown = active !== null ? points[active] : null;
  const total = points.reduce((n, p) => n + p.value, 0);

  const onKey = (e: React.KeyboardEvent) => {
    if (!points.length) return;
    if (e.key === 'ArrowRight') { e.preventDefault(); setActive((a) => Math.min(points.length - 1, (a ?? -1) + 1)); }
    if (e.key === 'ArrowLeft') { e.preventDefault(); setActive((a) => Math.max(0, (a ?? points.length) - 1)); }
    if (e.key === 'Escape') setActive(null);
  };

  return (
    <figure className="min-w-0">
      <figcaption className="flex items-baseline justify-between gap-2">
        <span className="text-sm font-medium">{title}</span>
        <span className="text-xs text-muted-foreground">{compact(total)} in total</span>
      </figcaption>
      {/* mt-9 reserves the strip the tooltip appears in, so it never covers the caption or the bars. */}
      <div className="relative mt-9 flex gap-2">
        <div aria-hidden="true" className="flex h-40 flex-col justify-between text-right text-[11px] tabular-nums text-muted-foreground">
          {ticks.map((t) => <span key={t} className="leading-none">{compact(t)}</span>)}
        </div>
        <div
          role="group"
          tabIndex={0}
          aria-label={`${title}: ${compact(total)} in total. Use the arrow keys to read each day, or show the table.`}
          onKeyDown={onKey}
          onBlur={() => setActive(null)}
          onMouseLeave={() => setActive(null)}
          className="relative h-40 min-w-0 flex-1 rounded focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {ticks.map((t) => (
            <div key={t} aria-hidden="true" className="absolute inset-x-0 border-t border-border" style={{ top: `${(1 - t / max) * 100}%` }} />
          ))}
          <div className="absolute inset-0 flex items-end gap-[2px]">
            {points.map((p, i) => (
              <div key={p.date} className="flex h-full min-w-0 flex-1 items-end justify-center" onMouseEnter={() => setActive(i)}>
                <div
                  className={cn('w-full max-w-[24px] rounded-t bg-primary transition-opacity', active !== null && active !== i && 'opacity-60')}
                  style={{ height: `${(p.value / max) * 100}%`, minHeight: p.value > 0 ? 2 : 0 }}
                />
              </div>
            ))}
          </div>
          {shown && active !== null && (
            <div role="status"
              // Above the day being read, kept inside the chart at both ends.
              style={{ left: `${((active + 0.5) / points.length) * 100}%`, transform: `translate(-${Math.round(((active + 0.5) / points.length) * 100)}%, -100%)` }}
              className="pointer-events-none absolute -top-2 z-10 whitespace-nowrap rounded-md border bg-popover px-2 py-1 text-xs shadow-sm">
              <span className="font-semibold tabular-nums">{compact(shown.value)}</span> <span className="text-muted-foreground">{valueLabel} on {shortDay(shown.date)}</span>
            </div>
          )}
        </div>
      </div>
      {points.length > 0 && (
        <div aria-hidden="true" className="ml-8 mt-1 flex justify-between text-[11px] text-muted-foreground">
          <span>{shortDay(points[0].date)}</span>
          <span>{shortDay(points[points.length - 1].date)}</span>
        </div>
      )}
    </figure>
  );
}

/** One row per group: name, a bar of the share met (lighter same-hue track), the percentage and the counts in words. */
export function MeterList({ rows, empty }: { rows: { name: string; value: number | null; detail: string }[]; empty: string }) {
  if (rows.length === 0) return <p className="text-sm text-muted-foreground">{empty}</p>;
  return (
    <ul className="space-y-3">
      {rows.map((r) => (
        // A fixed figures column keeps every track the same length, so the bars compare across rows.
        <li key={r.name} className="grid grid-cols-[minmax(0,8rem)_1fr_11.5rem] items-center gap-x-3 gap-y-1 text-sm max-sm:grid-cols-[1fr_auto]">
          <span className="truncate font-medium" title={r.name}>{r.name}</span>
          <div className="h-2 rounded-full bg-primary/15 max-sm:order-3 max-sm:col-span-2" aria-hidden="true">
            {r.value !== null && <div className="h-2 rounded-full bg-primary" style={{ width: `${r.value}%` }} />}
          </div>
          <span className="text-right tabular-nums"><span className="font-semibold">{pct(r.value)}</span> <span className="text-xs text-muted-foreground">{r.detail}</span></span>
        </li>
      ))}
    </ul>
  );
}

/** Ranked horizontal bars, one hue, largest first, the value at the tip. */
export function RankedBars({ rows, empty }: { rows: { name: string; value: number }[]; empty: string }) {
  const sorted = [...rows].filter((r) => r.value > 0).sort((a, b) => b.value - a.value);
  if (sorted.length === 0) return <p className="text-sm text-muted-foreground">{empty}</p>;
  const max = sorted[0].value;
  return (
    <ul className="space-y-2">
      {sorted.map((r) => (
        <li key={r.name} className="text-sm">
          <span className="block text-muted-foreground">{r.name}</span>
          <span className="mt-0.5 flex items-center gap-2">
            <span className="h-3 max-h-[24px] rounded-r bg-primary" style={{ width: `${Math.max(2, (r.value / max) * 85)}%` }} aria-hidden="true" />
            <span className="font-semibold tabular-nums">{compact(r.value)}</span>
          </span>
        </li>
      ))}
    </ul>
  );
}
