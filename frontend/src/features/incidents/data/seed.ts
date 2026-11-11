import { SEED_TECHNICIANS, SEED_USERS } from '@/features/admin/data/seed';
import { defaultVisibility } from '../model/lifecycle';
import type { Actor, EntryType, Feedback, Incident, IncidentState, Priority, TimelineEntry } from '../model/types';

/**
 * Fictitious demo data for the mock incident services (P2 will own real incident creation). Every address uses the
 * reserved `campus.example` domain. The data set is built per signed-in account so each persona has something to do:
 *  - a TECHNICIAN is assigned a handful of incidents,
 *  - an EMPLOYEE is the reporter of a handful,
 *  - an ADMIN sees everything and has unassigned incidents to hand out.
 * Dates are fixed so the seed is deterministic.
 */
export interface World {
  incidents: Incident[];
  timeline: TimelineEntry[];
  feedback: Feedback[];
}

const BASE = new Date('2026-11-06T09:15:00Z').getTime();
const at = (daysAgo: number, hour = 9) => {
  const t = new Date(BASE - daysAgo * 86_400_000);
  t.setUTCHours(hour, 15, 0, 0);
  return t.toISOString();
};

const user = (id: string) => {
  const u = SEED_USERS.find((x) => x.id === id)!;
  return { email: u.email, name: u.name };
};
const technicianUser = (techId: string) => user(SEED_TECHNICIANS.find((t) => t.id === techId)!.userId);

type Person = { email: string; name: string };

export function buildWorld(actor: Actor): World {
  const me: Person = { email: actor.email, name: actor.name };
  const asTech = (fallback: Person): Person => (actor.role === 'TECHNICIAN' ? me : fallback);
  const asReporter = (fallback: Person): Person => (actor.role === 'EMPLOYEE' ? me : fallback);

  const priya = technicianUser('t-001');
  const tomas = technicianUser('t-002');
  const meiling = technicianUser('t-003');
  const kofi = technicianUser('t-004');
  const sara = technicianUser('t-005');

  const incidents: Incident[] = [];
  const timeline: TimelineEntry[] = [];
  const feedback: Feedback[] = [];
  let seq = 0;

  const add = (
    id: string, title: string, description: string, category: string, service: string, priority: Priority, state: IncidentState,
    reporter: Person, assignee: Person | null, createdDaysAgo: number, updatedDaysAgo: number, resolvedDaysAgo: number | null = null
  ) => {
    incidents.push({
      id, title, description, category, service, priority, state,
      reporterEmail: reporter.email, reporterName: reporter.name,
      assigneeEmail: assignee?.email ?? null, assigneeName: assignee?.name ?? null,
      createdAt: at(createdDaysAgo), updatedAt: at(updatedDaysAgo, 14), resolvedAt: resolvedDaysAgo === null ? null : at(resolvedDaysAgo, 15),
    });
  };
  const entry = (incidentId: string, author: Person, type: EntryType, body: string, state: IncidentState, daysAgo: number, hour = 10, visibleToReporter?: boolean) => {
    timeline.push({
      id: `e-${++seq}`, incidentId, authorEmail: author.email, authorName: author.name, type,
      visibility: visibleToReporter === undefined ? defaultVisibility(type) : visibleToReporter ? 'REPORTER_VISIBLE' : 'INTERNAL',
      body, incidentState: state, createdAt: at(daysAgo, hour),
    });
  };

  // ---- Active work
  const techA = asTech(priya);
  const rAisha = asReporter(user('u-013'));
  add('INC1001', 'Wi-Fi drops every few minutes in the library reading room',
    'Laptops lose the connection roughly every five minutes. It started this morning and affects the whole reading room.',
    'Wi-Fi', 'Campus Wi-Fi', 'P2', 'ASSIGNED', rAisha, techA, 1, 0);

  const rElena = user('u-009');
  add('INC1002', 'VPN rejects every staff login',
    'Since 08:00 nobody in Finance can connect to the VPN. Everyone gets “authentication failed”. Month-end close is blocked.',
    'VPN & Remote', 'VPN Gateway', 'P1', 'IN_PROGRESS', rElena, techA, 1, 0);
  entry('INC1002', techA, 'NOTE', 'Gateway is up. Checking whether the RADIUS server is rejecting the requests.', 'ACKNOWLEDGED', 1, 9);
  entry('INC1002', techA, 'FINDING', 'RADIUS log shows the shared secret was rotated at 07:55 and the gateway still has the old one.', 'IN_PROGRESS', 0, 8, true);
  entry('INC1002', techA, 'ACTION_TAKEN', 'Updated the secret on the gateway in the maintenance window; testing with one account.', 'IN_PROGRESS', 0, 9);

  const rLuis = asReporter(user('u-012'));
  add('INC1003', 'Printer on floor 2 prints blank pages',
    'Every job from the floor 2 printer comes out blank after the toner change.',
    'Printers', 'Print Service', 'P3', 'PENDING_INFO', rLuis, asTech(tomas), 3, 1);
  entry('INC1003', asTech(tomas), 'INFO_REQUEST', 'Which printer is it? The label on the front shows a model and a number starting with PRN-.', 'IN_PROGRESS', 1, 11);

  const rHannah = asReporter(user('u-011'));
  add('INC1004', 'Cannot open the shared finance drive',
    'The drive shows “access denied” since this morning. It worked yesterday.',
    'Windows', 'File Storage', 'P3', 'RESOLVED', rHannah, asTech(meiling), 4, 1, 1);
  entry('INC1004', asTech(meiling), 'NOTE', 'Permissions look right on the share. Checking group membership.', 'IN_PROGRESS', 3, 10);
  entry('INC1004', asTech(meiling), 'RESOLUTION', 'Your account had dropped out of the Finance group during the weekend sync. I added it back; please sign out and in again.', 'IN_PROGRESS', 1, 15);

  const rOliver = user('u-014');
  add('INC1011', 'Account locked after resetting my password',
    'I reset my password this morning and now it says my account is locked.',
    'Identity & Access', 'Single Sign-On', 'P2', 'ACKNOWLEDGED', rOliver, asTech(meiling), 1, 0);

  // ---- Waiting for assignment (admin hands these out)
  add('INC1005', 'Email to students is delayed by hours',
    'Messages to student addresses arrive two to three hours late. Staff addresses are fine.',
    'Email & Calendar', 'Email', 'P1', 'CLASSIFIED', asReporter(user('u-010')), null, 0, 0);
  add('INC1006', 'Request: Linux VM tooling on the lab machines',
    'We need the course VM tooling installed on the thirty machines in lab 4 before the next term.',
    'Linux', 'Computer Labs', 'P4', 'CLASSIFIED', user('u-015'), null, 2, 2);

  // ---- Fixed, waiting for the reporter's feedback / closing
  add('INC1007', 'Projector in room B12 has no sound',
    'The picture works but there is no audio from the room speakers.',
    'Audio/Visual', 'Classroom Technology', 'P3', 'VERIFIED', asReporter(user('u-019')), asTech(kofi), 6, 2, 3);
  entry('INC1007', asTech(kofi), 'RESOLUTION', 'The HDMI audio output was muted on the lectern PC. Unmuted it and set it as the default device.', 'IN_PROGRESS', 3, 14);

  // ---- History, used for "how similar incidents were fixed"
  add('INC1008', 'Grade export fails with a database timeout',
    'The end-of-term grade export times out after about a minute.',
    'Database', 'Student Records', 'P2', 'CLOSED', user('u-011'), sara, 30, 22, 23);
  entry('INC1008', sara, 'RESOLUTION', 'The connection pool was capped at 10 and the export held all of them. Raised the cap to 40 and split the export into batches.', 'IN_PROGRESS', 23, 15);
  feedback.push({ incidentId: 'INC1008', reporterEmail: user('u-011').email, rating: 5, comment: 'Quick and clear explanation, thank you.', submittedAt: at(21) });

  add('INC1009', 'Print queue stuck after the driver update',
    'Nothing prints from the third floor; jobs sit in the queue for ever.',
    'Printers', 'Print Service', 'P3', 'CLOSED', user('u-017'), tomas, 18, 14, 15);
  entry('INC1009', tomas, 'RESOLUTION', 'The print spooler was holding a corrupt job from before the update. Stopped the service, cleared the spool folder, restarted it.', 'IN_PROGRESS', 15, 11);
  feedback.push({ incidentId: 'INC1009', reporterEmail: user('u-017').email, rating: 4, comment: null, submittedAt: at(13) });

  add('INC1010', 'Devices in Block C cannot get an IP address',
    'Phones and laptops connect to the Wi-Fi but never get an address.',
    'Wi-Fi', 'Campus Wi-Fi', 'P2', 'CLOSED', user('u-021'), priya, 40, 38, 38);
  entry('INC1010', priya, 'RESOLUTION', 'The DHCP scope for Block C was exhausted by stale leases. Shortened the lease time and extended the pool.', 'IN_PROGRESS', 38, 13);
  feedback.push({ incidentId: 'INC1010', reporterEmail: user('u-021').email, rating: 5, comment: null, submittedAt: at(36) });

  add('INC1012', 'VPN disconnects after ten minutes',
    'Remote staff are dropped from the VPN after exactly ten minutes.',
    'VPN & Remote', 'VPN Gateway', 'P2', 'CLOSED', user('u-016'), priya, 55, 52, 52);
  entry('INC1012', priya, 'RESOLUTION', 'An idle timeout of 600 seconds had been applied by a policy update. Raised it to 8 hours for the staff group.', 'IN_PROGRESS', 52, 12);

  return { incidents, timeline, feedback };
}
