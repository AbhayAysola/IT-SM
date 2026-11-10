import { Badge } from '@/components/ui/badge';
import { PRIORITY_LABEL, CRITICALITY_LABEL, type AccountStatus, type ActiveStatus, type AdminRole, type Availability, type Criticality, type Priority, type SkillLevel } from '../types';

export const ROLE_LABEL: Record<AdminRole, string> = { EMPLOYEE: 'Employee', TECHNICIAN: 'Technician', ADMIN: 'Administrator' };
export const STATUS_LABEL: Record<AccountStatus, string> = { ACTIVE: 'Active', SUSPENDED: 'Suspended', INVITED: 'Invited' };
export const AVAILABILITY_LABEL: Record<Availability, string> = { AVAILABLE: 'Available', BUSY: 'Busy', OFF_SHIFT: 'Off shift', ON_LEAVE: 'On leave' };
export const SKILL_LEVEL_LABEL: Record<SkillLevel, string> = { JUNIOR: 'Junior', INTERMEDIATE: 'Intermediate', SENIOR: 'Senior' };

export const RoleBadge = ({ role }: { role: AdminRole }) => (
  <Badge variant={role === 'ADMIN' ? 'default' : 'secondary'}>{ROLE_LABEL[role]}</Badge>
);

export const StatusBadge = ({ status }: { status: AccountStatus }) => (
  <Badge variant={status === 'ACTIVE' ? 'success' : status === 'SUSPENDED' ? 'destructive' : 'warning'}>{STATUS_LABEL[status]}</Badge>
);

export const AvailabilityBadge = ({ value }: { value: Availability }) => (
  <Badge variant={value === 'AVAILABLE' ? 'success' : value === 'BUSY' ? 'warning' : 'secondary'}>{AVAILABILITY_LABEL[value]}</Badge>
);

export const formatDate = (iso: string | null) =>
  iso ? new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' }) : 'Never';

export const formatDateTime = (iso: string) =>
  new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: 'UTC' }) + ' UTC';

export const ActiveBadge = ({ status }: { status: ActiveStatus }) => (
  <Badge variant={status === 'ACTIVE' ? 'success' : 'secondary'}>{status === 'ACTIVE' ? 'Active' : 'Inactive'}</Badge>
);

export const PriorityBadge = ({ priority }: { priority: Priority }) => (
  <Badge variant={priority === 'P1' ? 'destructive' : priority === 'P2' ? 'warning' : priority === 'P3' ? 'secondary' : 'outline'}>{PRIORITY_LABEL[priority]}</Badge>
);

export const CriticalityBadge = ({ value }: { value: Criticality }) => (
  <Badge variant={value === 'CRITICAL' ? 'destructive' : value === 'HIGH' ? 'warning' : value === 'MEDIUM' ? 'secondary' : 'outline'}>{CRITICALITY_LABEL[value]}</Badge>
);
