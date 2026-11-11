import { Badge } from '@/components/ui/badge';
import { stateLabel } from '../model/lifecycle';
import type { Audience, IncidentState } from '../model/types';

export { PriorityBadge } from '@/features/admin/components/status-badges';

const VARIANT: Record<IncidentState, 'default' | 'secondary' | 'outline' | 'success' | 'warning' | 'destructive'> = {
  NEW: 'outline', CLASSIFIED: 'outline', ASSIGNED: 'secondary', ACKNOWLEDGED: 'secondary', IN_PROGRESS: 'default',
  PENDING_INFO: 'warning', RESOLVED: 'warning', VERIFIED: 'success', CLOSED: 'secondary',
};

/** The words always accompany the colour (PRODUCT.md principle 1). */
export function StateBadge({ state, audience }: { state: IncidentState; audience: Audience }) {
  return <Badge variant={VARIANT[state]}>{stateLabel(state, audience)}</Badge>;
}

export const formatWhen = (iso: string) =>
  new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: 'UTC' }) + ' UTC';

export const formatDay = (iso: string) =>
  new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
