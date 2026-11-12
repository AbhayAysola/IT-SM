import { PRIORITIES, type AdminRole, type Priority } from '../types';
import { assignmentApi } from './assignment-api';
import { categoryApi } from './category-api';
import { itServiceApi } from './it-service-api';
import { slaApi } from './sla-api';
import { technicianApi } from './technician-api';
import { userApi } from './user-api';

export interface AdminOverview {
  users: { total: number; active: number; inactive: number; byRole: { role: AdminRole; count: number }[] };
  technicians: { activeProfiles: number; notAvailable: number };
  catalog: { categories: number; services: number; slaPolicies: number };
  attention: { id: string; label: string; detail: string; href: string }[];
}

const one = { size: 1 } as const;

/**
 * The overview has no endpoint of its own: its figures are `totalItems` of ordinary list calls (page size 1), so every
 * number is what the real list pages would show for the same filter. Nothing here is stored or simulated.
 */
export async function loadOverview(): Promise<AdminOverview> {
  const [all, active, employees, technicians, admins, techActive, techOff, cats, services, slas, globals, assignment] = await Promise.all([
    userApi.list(one),
    userApi.list({ ...one, active: 'true' }),
    userApi.list({ ...one, role: 'EMPLOYEE' }),
    userApi.list({ ...one, role: 'TECHNICIAN' }),
    userApi.list({ ...one, role: 'ADMIN' }),
    technicianApi.list({ ...one, active: 'true' }),
    technicianApi.list({ ...one, active: 'true', available: 'false' }),
    categoryApi.list({ ...one, active: 'true' }),
    itServiceApi.list({ ...one, active: 'true' }),
    slaApi.list({ ...one, active: 'true' }),
    slaApi.list({ size: 10, active: 'true', global: true }),
    assignmentApi.get(),
  ]);

  const attention: AdminOverview['attention'] = [];
  const inactive = all.totalItems - active.totalItems;
  if (inactive > 0) attention.push({ id: 'inactive', label: `${inactive} inactive account${inactive > 1 ? 's' : ''}`, detail: 'Review whether access should be restored.', href: '/admin/users?active=false' });
  if (techOff.totalItems > 0) attention.push({ id: 'unavailable', label: `${techOff.totalItems} technician${techOff.totalItems > 1 ? 's' : ''} marked not available`, detail: 'An administrator turned off their eligibility.', href: '/admin/technicians' });
  const covered = new Set<Priority>(globals.items.map((p) => p.priority));
  const missing = PRIORITIES.filter((p) => !covered.has(p));
  if (missing.length) attention.push({ id: 'sla', label: `No global SLA policy for ${missing.join(', ')}`, detail: 'Services without their own policy have no target for these priorities.', href: '/admin/sla-policies' });
  if (cats.totalItems === 0) attention.push({ id: 'categories', label: 'No active incident category', detail: 'New incidents cannot be classified.', href: '/admin/categories' });
  const link = '/admin/assignment-settings';
  if (assignment.strategyCatalogStatus === 'ABSENT') {
    attention.push({ id: 'catalog', label: 'Assignment strategy catalog not deployed yet', detail: 'Assignment settings cannot be changed until it is deployed.', href: link });
  } else if (assignment.strategyCatalogStatus === 'FAILED') {
    attention.push({ id: 'catalog', label: 'Assignment strategy catalog could not be reached', detail: 'A temporary problem: settings are read-only until it answers again.', href: link });
  } else if ((assignment.availableStrategies ?? []).length === 0) {
    attention.push({ id: 'catalog', label: 'No assignment strategies are registered', detail: 'The catalog answered but lists none, so settings cannot be changed.', href: link });
  } else {
    const registered = new Set(assignment.availableStrategies);
    const stale = [assignment.activeStrategy, ...assignment.categoryOverrides.map((o) => o.strategy)].filter((k) => !registered.has(k));
    if (stale.length) {
      const keys = Array.from(new Set(stale));
      attention.push({ id: 'strategy', label: `Saved assignment strategy not registered: ${keys.join(', ')}`, detail: 'Choose a registered strategy and save.', href: link });
    }
  }

  return {
    users: {
      total: all.totalItems, active: active.totalItems, inactive,
      byRole: [
        { role: 'EMPLOYEE', count: employees.totalItems }, { role: 'TECHNICIAN', count: technicians.totalItems }, { role: 'ADMIN', count: admins.totalItems },
      ],
    },
    technicians: { activeProfiles: techActive.totalItems, notAvailable: techOff.totalItems },
    catalog: { categories: cats.totalItems, services: services.totalItems, slaPolicies: slas.totalItems },
    attention,
  };
}
