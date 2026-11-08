'use client';

import React, { useEffect, useState } from 'react';
import { Shuffle } from 'lucide-react';
import { Card } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { PageHeader } from '../components/page-header';
import { EmptyState, ErrorState } from '../components/states';
import { useToast } from '../components/toast';
import { useResource } from '../hooks/use-resource';
import { assignmentService, STRATEGY_INFO } from '../services/assignment-service';
import { AssignmentForm } from './assignment-form';
import { AssignmentSummary } from './assignment-summary';

export default function AssignmentSettingsPage() {
  const toast = useToast();
  const settings = useResource(() => assignmentService.get(), []);
  const teams = useResource(() => assignmentService.teamsWithTechnicians(), []);

  // Show the skeleton only for the first load, so saving doesn't blank the page.
  const [ready, setReady] = useState(false);
  useEffect(() => { if (!settings.loading && teams.data) setReady(true); }, [settings.loading, teams.data]);
  const error = settings.error ?? teams.error;
  const reload = () => { settings.reload(); teams.reload(); };
  const record = settings.data;

  return (
    <>
      <PageHeader
        title="Assignment settings"
        description="Choose how new incidents are matched to technicians. This page only records the configuration; the routing engine applies it."
      />

      {error ? (
        <Card><ErrorState message={error} onRetry={reload} /></Card>
      ) : !ready || !teams.data ? (
        <div role="status" aria-label="Loading assignment settings" className="space-y-6">
          <Skeleton className="h-48" /><Skeleton className="h-96" />
        </div>
      ) : (
        <div className="space-y-6">
          {record ? (
            <AssignmentSummary settings={record} />
          ) : (
            <Card>
              <EmptyState icon={Shuffle} title="No assignment strategy configured"
                description={`Incidents can only be assigned by hand until you choose a strategy. Pick one below. Available: ${Object.values(STRATEGY_INFO).map((s) => s.label).join(', ')}.`} />
            </Card>
          )}
          <AssignmentForm
            key={record?.updatedAt ?? 'new'}
            saved={record}
            teamsWithTechnicians={teams.data}
            onSaved={(r) => { toast.success(`Assignment settings saved: ${STRATEGY_INFO[r.strategy].label}.`); settings.reload(); }}
          />
        </div>
      )}
    </>
  );
}
