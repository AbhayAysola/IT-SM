import { Badge } from '@/components/ui/badge';
import { CRITICALITY_LABEL, PRIORITY_LABEL, type AdminRole, type Priority } from '../types';

export const ROLE_LABEL: Record<AdminRole, string> = { EMPLOYEE: 'Employee', TECHNICIAN: 'Technician', ADMIN: 'Administrator' };

export const RoleBadge = ({ role }: { role: AdminRole }) => (
  <Badge variant={role === 'ADMIN' ? 'default' : 'secondary'}>{ROLE_LABEL[role]}</Badge>
);

export const ActiveBadge = ({ active, activeLabel = 'Active', inactiveLabel = 'Inactive' }: { active: boolean; activeLabel?: string; inactiveLabel?: string }) => (
  <Badge variant={active ? 'success' : 'secondary'}>{active ? activeLabel : inactiveLabel}</Badge>
);

export const PriorityBadge = ({ priority }: { priority: Priority }) => (
  <Badge variant={priority === 'P1' ? 'destructive' : priority === 'P2' ? 'warning' : priority === 'P3' ? 'secondary' : 'outline'}>{PRIORITY_LABEL[priority]}</Badge>
);

export const CriticalityBadge = ({ value }: { value: number }) => (
  <Badge variant={value >= 5 ? 'destructive' : value === 4 ? 'warning' : value === 3 ? 'secondary' : 'outline'}>{CRITICALITY_LABEL[value] ?? String(value)}</Badge>
);

export const formatDate = (iso: string | null) =>
  iso ? new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }) : '—';

export const formatDateTime = (iso: string) =>
  new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: 'UTC' }) + ' UTC';

export const formatMinutes = (m: number) => {
  if (m < 60) return `${m} min`;
  const d = Math.floor(m / 1440), h = Math.floor((m % 1440) / 60), r = m % 60;
  return [d && `${d} d`, h && `${h} h`, r && `${r} min`].filter(Boolean).join(' ');
};
