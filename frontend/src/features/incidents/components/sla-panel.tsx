'use client';

import { useEffect, useState } from 'react';
import { Timer } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader } from '@/components/ui/card';
import { CardHeading } from './card-heading';
import { formatWhen } from './badges';
import { clockLine, SLA_LABEL, SLA_VARIANT } from '../model/sla';
import type { SlaClockView, SlaStatus, SlaSummary } from '../model/types';

function SlaBadge({ status }: { status: SlaStatus }) {
  return <Badge variant={SLA_VARIANT[status]}>{SLA_LABEL[status]}</Badge>;
}

function ClockRow({ name, clock, now }: { name: string; clock: SlaClockView | null; now: number }) {
  if (!clock) return null;
  return (
    <div>
      <dt className="flex items-center justify-between gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
        <span>{name}{clock.targetMinutes !== null && <span className="normal-case tracking-normal"> · {formatTarget(clock.targetMinutes)}</span>}</span>
        <SlaBadge status={clock.status} />
      </dt>
      <dd className="mt-0.5">{clockLine(clock, now, formatWhen)}</dd>
    </div>
  );
}

const formatTarget = (minutes: number) =>
  minutes % 1440 === 0 ? `${minutes / 1440} day target` : minutes % 60 === 0 ? `${minutes / 60} h target` : `${minutes} min target`;

/**
 * The incident's response and resolution clocks. Deadlines are absolute, so the "in 23 min" text is refreshed every
 * 30 seconds without asking the server again; statuses come from the server, which re-evaluates them every minute.
 */
export function SlaPanel({ sla }: { sla: SlaSummary }) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(id);
  }, []);

  return (
    <Card data-testid="sla-panel">
      <CardHeader>
        <div className="flex items-center justify-between gap-2">
          <CardHeading className="flex items-center gap-2"><Timer className="h-4 w-4" aria-hidden="true" /> Service level</CardHeading>
          <SlaBadge status={sla.status} />
        </div>
        <CardDescription>
          {sla.status === 'PENDING' && 'Deadlines appear within a minute of the incident being classified.'}
          {sla.status === 'NO_POLICY' && 'No SLA policy covers this service and priority. An administrator has been told.'}
          {sla.status !== 'PENDING' && sla.status !== 'NO_POLICY' && 'Time to first response and to resolution, from when it was reported.'}
        </CardDescription>
      </CardHeader>
      {(sla.response || sla.resolution) && sla.status !== 'NO_POLICY' && (
        <CardContent>
          <dl className="space-y-3 text-sm">
            <ClockRow name="Response" clock={sla.response} now={now} />
            <ClockRow name="Resolution" clock={sla.resolution} now={now} />
          </dl>
          {sla.evaluatedAt && <p className="mt-3 text-xs text-muted-foreground">Checked {formatWhen(sla.evaluatedAt)}</p>}
        </CardContent>
      )}
    </Card>
  );
}
