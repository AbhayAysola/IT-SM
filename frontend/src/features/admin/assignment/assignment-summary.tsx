import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { formatDateTime } from '../components/status-badges';
import { summarizeAssignmentSettings } from '../services/assignment-service';
import type { AssignmentSettingsRecord } from '../types';

export function AssignmentSummary({ settings }: { settings: AssignmentSettingsRecord }) {
  const { headline, lines } = summarizeAssignmentSettings(settings);
  return (
    <Card data-testid="assignment-summary">
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <CardTitle className="text-lg">Current configuration</CardTitle>
          <Badge variant="success">In use</Badge>
        </div>
        <CardDescription>{headline}</CardDescription>
      </CardHeader>
      <CardContent>
        <dl className="grid gap-x-8 gap-y-3 sm:grid-cols-2">
          {lines.map((l) => (
            <div key={l.label} className="min-w-0">
              <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{l.label}</dt>
              <dd className="mt-0.5 text-sm font-medium text-foreground">{l.value}</dd>
            </div>
          ))}
        </dl>
        <p className="mt-4 border-t pt-3 text-xs text-muted-foreground">
          Last changed by {settings.updatedBy} on <time dateTime={settings.updatedAt}>{formatDateTime(settings.updatedAt)}</time>
        </p>
      </CardContent>
    </Card>
  );
}
