import { LayoutDashboard, ScrollText, Server, Shuffle, Siren, Tags, Timer, UserCog, Users, type LucideIcon } from 'lucide-react';

export interface AdminNavItem {
  title: string;
  href: string;
  icon: LucideIcon;
}

export interface AdminNavGroup {
  label: string;
  items: AdminNavItem[];
}

/** Only routes that exist. Later batches append their pages here. */
export const ADMIN_NAV: AdminNavGroup[] = [
  { label: 'Overview', items: [{ title: 'Admin overview', href: '/admin', icon: LayoutDashboard }] },
  {
    label: 'People',
    items: [
      { title: 'Users', href: '/admin/users', icon: Users },
      { title: 'Technicians', href: '/admin/technicians', icon: UserCog },
    ],
  },
  {
    label: 'Configuration',
    items: [
      { title: 'Categories', href: '/admin/categories', icon: Tags },
      { title: 'IT services', href: '/admin/services', icon: Server },
      { title: 'SLA policies', href: '/admin/sla-policies', icon: Timer },
      { title: 'Escalation', href: '/admin/escalation-settings', icon: Siren },
      { title: 'Assignment settings', href: '/admin/assignment-settings', icon: Shuffle },
    ],
  },
  { label: 'Governance', items: [{ title: 'Audit logs', href: '/admin/audit-logs', icon: ScrollText }] },
];
