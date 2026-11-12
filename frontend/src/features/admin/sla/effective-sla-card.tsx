'use client';

import React, { useState } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FormField } from '../components/form-field';
import { formatMinutes } from '../components/status-badges';
import { useResource } from '../hooks/use-resource';
import { itServiceApi } from '../services/it-service-api';
import { slaApi } from '../services/sla-api';
import { PRIORITIES, PRIORITY_LABEL, ServiceError, type EffectiveSla, type Priority } from '../types';

type Outcome = { kind: 'ok'; value: EffectiveSla } | { kind: 'none'; message: string } | { kind: 'error'; message: string };

/** Read-only preview of what the SLA provider returns today: the service's own policy, else the global one, else nothing. */
export function EffectiveSlaCard({ refreshKey }: { refreshKey: number }) {
  const services = useResource(() => itServiceApi.list({ sort: 'name', size: 100 }), [refreshKey]);
  const [serviceId, setServiceId] = useState('');
  const [priority, setPriority] = useState<Priority>('P3');
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const check = async () => {
    setBusy(true); setOutcome(null);
    try { setOutcome({ kind: 'ok', value: await slaApi.effective(Number(serviceId), priority) }); }
    catch (e) {
      const err = e instanceof ServiceError ? e : null;
      setOutcome(err?.code === 'UNPROCESSABLE' ? { kind: 'none', message: err.message } : { kind: 'error', message: err?.message ?? 'Could not check.' });
    } finally { setBusy(false); }
  };

  return (
    <Card data-testid="effective-sla">
      <CardHeader>
        <CardTitle className="text-lg">Effective policy preview</CardTitle>
        <CardDescription>What would apply to a service and priority right now. A service’s own active policy wins; otherwise the global one; if neither exists, nothing applies.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-[1fr_12rem_auto] sm:items-end">
          <FormField id="eff-service" label="Service">
            <Select value={serviceId} onValueChange={(x) => { setServiceId(x); setOutcome(null); }}>
              <SelectTrigger id="eff-service"><SelectValue placeholder={services.loading ? 'Loading…' : 'Choose a service…'} /></SelectTrigger>
              <SelectContent>{(services.data?.items ?? []).map((s) => <SelectItem key={s.id} value={String(s.id)}>{s.name}{s.active ? '' : ' (inactive)'}</SelectItem>)}</SelectContent>
            </Select>
          </FormField>
          <FormField id="eff-priority" label="Priority">
            <Select value={priority} onValueChange={(x) => { setPriority(x as Priority); setOutcome(null); }}>
              <SelectTrigger id="eff-priority"><SelectValue /></SelectTrigger>
              <SelectContent>{PRIORITIES.map((p) => <SelectItem key={p} value={p}>{PRIORITY_LABEL[p]}</SelectItem>)}</SelectContent>
            </Select>
          </FormField>
          <Button onClick={check} disabled={!serviceId || busy}>{busy && <Loader2 className="animate-spin" aria-hidden="true" />} Check</Button>
        </div>
        {services.error && <p role="alert" className="text-sm text-destructive">{services.error}</p>}
        <div aria-live="polite" data-testid="effective-result">
          {outcome?.kind === 'ok' && (
            <p className="text-sm">
              <strong>{outcome.value.scope === 'SERVICE' ? 'Service-specific policy' : 'Global policy'}</strong> (#{outcome.value.policyId}):
              respond within {formatMinutes(outcome.value.responseMinutes)}, resolve within {formatMinutes(outcome.value.resolutionMinutes)}, warn at {outcome.value.warningThresholdPct}%.
            </p>
          )}
          {outcome?.kind === 'none' && <p role="status" className="text-sm font-medium text-warning">No policy applies. {outcome.message}</p>}
          {outcome?.kind === 'error' && <p role="alert" className="text-sm font-medium text-destructive">{outcome.message}</p>}
        </div>
      </CardContent>
    </Card>
  );
}
