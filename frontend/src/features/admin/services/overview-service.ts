import { SEED_TECHNICIANS, SEED_USERS } from '../data/seed';
import type { ManagedUser, TechnicianProfile } from '../types';
import { loadCollection, simulate } from './store';

export interface AdminOverview {
  totalUsers: number;
  activeUsers: number;
  invitedUsers: number;
  suspendedUsers: number;
  byRole: { role: 'EMPLOYEE' | 'TECHNICIAN' | 'ADMIN'; count: number }[];
  technicians: {
    total: number;
    available: number;
    overCapacity: number;
    utilisationPct: number;
    withoutSkills: number;
  };
  attention: { id: string; label: string; detail: string; href: string }[];
}

export const overviewService = {
  get: (): Promise<AdminOverview> =>
    simulate(() => {
      const users = loadCollection<ManagedUser>('users', SEED_USERS);
      const techs = loadCollection<TechnicianProfile>('technicians', SEED_TECHNICIANS);
      const cap = techs.reduce((s, t) => s + t.maxWorkload, 0);
      const cur = techs.reduce((s, t) => s + t.currentWorkload, 0);
      const overCapacity = techs.filter((t) => t.currentWorkload >= t.maxWorkload).length;
      const withoutSkills = techs.filter((t) => t.skills.length === 0).length;
      const invited = users.filter((u) => u.status === 'INVITED').length;
      const suspended = users.filter((u) => u.status === 'SUSPENDED').length;

      const attention: AdminOverview['attention'] = [];
      if (invited) attention.push({ id: 'invited', label: `${invited} pending invitation${invited > 1 ? 's' : ''}`, detail: 'People invited who have not signed in yet.', href: '/admin/users?status=INVITED' });
      if (suspended) attention.push({ id: 'suspended', label: `${suspended} suspended account${suspended > 1 ? 's' : ''}`, detail: 'Review whether access should be restored.', href: '/admin/users?status=SUSPENDED' });
      if (overCapacity) attention.push({ id: 'capacity', label: `${overCapacity} technician${overCapacity > 1 ? 's' : ''} at full workload`, detail: 'New incidents cannot be routed to them.', href: '/admin/technicians?sort=workload' });
      if (withoutSkills) attention.push({ id: 'skills', label: `${withoutSkills} technician${withoutSkills > 1 ? 's' : ''} with no skills`, detail: 'Skill-based routing will skip them.', href: '/admin/technicians' });

      return {
        totalUsers: users.length,
        activeUsers: users.filter((u) => u.status === 'ACTIVE').length,
        invitedUsers: invited,
        suspendedUsers: suspended,
        byRole: (['EMPLOYEE', 'TECHNICIAN', 'ADMIN'] as const).map((role) => ({ role, count: users.filter((u) => u.role === role).length })),
        technicians: {
          total: techs.length,
          available: techs.filter((t) => t.availability === 'AVAILABLE').length,
          overCapacity,
          utilisationPct: cap ? Math.round((cur / cap) * 100) : 0,
          withoutSkills,
        },
        attention,
      };
    }),
};
