import type { AuditEntry, ManagedUser, TechnicianProfile } from '../types';

const d = (daysAgo: number, hour = 9) => {
  const t = new Date('2026-11-06T00:00:00Z');
  t.setUTCDate(t.getUTCDate() - daysAgo);
  t.setUTCHours(hour, 15, 0, 0);
  return t.toISOString();
};

export const SEED_USERS: ManagedUser[] = [
  { id: 'u-001', name: 'Amara Okafor', email: 'amara.okafor@campus.edu', role: 'ADMIN', department: 'Information Technology', status: 'ACTIVE', createdAt: d(420), lastLoginAt: d(0, 8) },
  { id: 'u-002', name: 'Daniel Whitfield', email: 'daniel.whitfield@campus.edu', role: 'ADMIN', department: 'Information Technology', status: 'ACTIVE', createdAt: d(390), lastLoginAt: d(1, 14) },
  { id: 'u-003', name: 'Priya Natarajan', email: 'priya.natarajan@campus.edu', role: 'TECHNICIAN', department: 'Information Technology', status: 'ACTIVE', createdAt: d(380), lastLoginAt: d(0, 7) },
  { id: 'u-004', name: 'Tomás Herrera', email: 'tomas.herrera@campus.edu', role: 'TECHNICIAN', department: 'Information Technology', status: 'ACTIVE', createdAt: d(360), lastLoginAt: d(0, 9) },
  { id: 'u-005', name: 'Mei-Ling Chen', email: 'meiling.chen@campus.edu', role: 'TECHNICIAN', department: 'Information Technology', status: 'ACTIVE', createdAt: d(340), lastLoginAt: d(2, 10) },
  { id: 'u-006', name: 'Kofi Mensah', email: 'kofi.mensah@campus.edu', role: 'TECHNICIAN', department: 'Information Technology', status: 'ACTIVE', createdAt: d(300), lastLoginAt: d(1, 16) },
  { id: 'u-007', name: 'Sara Lindqvist', email: 'sara.lindqvist@campus.edu', role: 'TECHNICIAN', department: 'Information Technology', status: 'ACTIVE', createdAt: d(280), lastLoginAt: d(3, 11) },
  { id: 'u-008', name: 'Rahul Verma', email: 'rahul.verma@campus.edu', role: 'TECHNICIAN', department: 'Information Technology', status: 'SUSPENDED', createdAt: d(250), lastLoginAt: d(40, 9) },
  { id: 'u-009', name: 'Elena Petrova', email: 'elena.petrova@campus.edu', role: 'EMPLOYEE', department: 'Finance', status: 'ACTIVE', createdAt: d(200), lastLoginAt: d(0, 10) },
  { id: 'u-010', name: 'James Abara', email: 'james.abara@campus.edu', role: 'EMPLOYEE', department: 'Human Resources', status: 'ACTIVE', createdAt: d(198), lastLoginAt: d(4, 13) },
  { id: 'u-011', name: 'Hannah Becker', email: 'hannah.becker@campus.edu', role: 'EMPLOYEE', department: 'Academic Affairs', status: 'ACTIVE', createdAt: d(190), lastLoginAt: d(1, 9) },
  { id: 'u-012', name: 'Luis Fernandez', email: 'luis.fernandez@campus.edu', role: 'EMPLOYEE', department: 'Facilities', status: 'ACTIVE', createdAt: d(180), lastLoginAt: d(6, 8) },
  { id: 'u-013', name: 'Aisha Rahman', email: 'aisha.rahman@campus.edu', role: 'EMPLOYEE', department: 'Library', status: 'ACTIVE', createdAt: d(170), lastLoginAt: d(0, 12) },
  { id: 'u-014', name: 'Oliver Grant', email: 'oliver.grant@campus.edu', role: 'EMPLOYEE', department: 'Student Services', status: 'ACTIVE', createdAt: d(160), lastLoginAt: d(2, 15) },
  { id: 'u-015', name: 'Nadia Bashir', email: 'nadia.bashir@campus.edu', role: 'EMPLOYEE', department: 'Research Office', status: 'ACTIVE', createdAt: d(150), lastLoginAt: d(9, 10) },
  { id: 'u-016', name: 'Victor Ng', email: 'victor.ng@campus.edu', role: 'EMPLOYEE', department: 'Procurement', status: 'ACTIVE', createdAt: d(140), lastLoginAt: d(12, 9) },
  { id: 'u-017', name: 'Grace Mwangi', email: 'grace.mwangi@campus.edu', role: 'EMPLOYEE', department: 'Finance', status: 'ACTIVE', createdAt: d(130), lastLoginAt: d(1, 11) },
  { id: 'u-018', name: 'Samuel Ortiz', email: 'samuel.ortiz@campus.edu', role: 'EMPLOYEE', department: 'Facilities', status: 'SUSPENDED', createdAt: d(120), lastLoginAt: d(60, 9) },
  { id: 'u-019', name: 'Yuki Tanaka', email: 'yuki.tanaka@campus.edu', role: 'EMPLOYEE', department: 'Academic Affairs', status: 'ACTIVE', createdAt: d(100), lastLoginAt: d(0, 9) },
  { id: 'u-020', name: 'Fatima Zahra', email: 'fatima.zahra@campus.edu', role: 'EMPLOYEE', department: 'Human Resources', status: 'ACTIVE', createdAt: d(90), lastLoginAt: d(5, 14) },
  { id: 'u-021', name: 'Peter Novak', email: 'peter.novak@campus.edu', role: 'EMPLOYEE', department: 'Library', status: 'ACTIVE', createdAt: d(70), lastLoginAt: d(3, 9) },
  { id: 'u-022', name: 'Chiara Romano', email: 'chiara.romano@campus.edu', role: 'EMPLOYEE', department: 'Student Services', status: 'INVITED', createdAt: d(6), lastLoginAt: null },
  { id: 'u-023', name: 'Ben Adeyemi', email: 'ben.adeyemi@campus.edu', role: 'EMPLOYEE', department: 'Research Office', status: 'INVITED', createdAt: d(3), lastLoginAt: null },
  { id: 'u-024', name: 'Ingrid Solheim', email: 'ingrid.solheim@campus.edu', role: 'EMPLOYEE', department: 'Procurement', status: 'ACTIVE', createdAt: d(45), lastLoginAt: d(7, 10) },
  { id: 'u-025', name: 'Marcus Bell', email: 'marcus.bell@campus.edu', role: 'EMPLOYEE', department: 'Finance', status: 'ACTIVE', createdAt: d(30), lastLoginAt: d(2, 9) },
];

const tech = (
  id: string, userId: string, skills: string[], skillLevel: TechnicianProfile['skillLevel'],
  availability: TechnicianProfile['availability'], max: number, cur: number, lead: boolean, team: string, shift: string
): TechnicianProfile => ({
  id, userId, skills, skillLevel, availability, maxWorkload: max, currentWorkload: cur, isTeamLead: lead, team, shift, updatedAt: d(5),
});

export const SEED_TECHNICIANS: TechnicianProfile[] = [
  tech('t-001', 'u-003', ['Networking', 'VPN & Remote', 'Wi-Fi', 'Security'], 'SENIOR', 'AVAILABLE', 8, 5, true, 'Infrastructure', 'Day (08:00-16:00)'),
  tech('t-002', 'u-004', ['Hardware', 'Windows', 'Printers'], 'INTERMEDIATE', 'BUSY', 6, 6, false, 'Service Desk', 'Day (08:00-16:00)'),
  tech('t-003', 'u-005', ['Email & Calendar', 'Identity & Access', 'Cloud'], 'SENIOR', 'AVAILABLE', 8, 3, true, 'Applications', 'Day (08:00-16:00)'),
  tech('t-004', 'u-006', ['macOS', 'Hardware', 'Audio/Visual'], 'INTERMEDIATE', 'AVAILABLE', 6, 2, false, 'Service Desk', 'Evening (14:00-22:00)'),
  tech('t-005', 'u-007', ['Database', 'Linux', 'Cloud'], 'SENIOR', 'OFF_SHIFT', 7, 4, false, 'Applications', 'Night (22:00-06:00)'),
  tech('t-006', 'u-008', ['Printers', 'Windows'], 'JUNIOR', 'ON_LEAVE', 4, 0, false, 'Service Desk', 'Day (08:00-16:00)'),
];

/**
 * Demo history so the audit viewer has something to show on a fresh install. Every entry is flagged `seed: true`
 * and is labelled "Demo data" in the UI. Real actions append new entries ahead of these.
 */
const a = (id: string, daysAgo: number, hour: number, actor: string, action: AuditEntry['action'], entity: AuditEntry['entity'], entityId: string, summary: string, before?: Record<string, string>, after?: Record<string, string>): AuditEntry =>
  ({ id, at: d(daysAgo, hour), actor, action, entity, entityId, summary, ...(before ? { before, after } : {}), seed: true });

const AMARA = 'Amara Okafor';
const DANIEL = 'Daniel Whitfield';

export const SEED_AUDIT: AuditEntry[] = [
  a('a-001', 60, 9, AMARA, 'CREATE', 'SERVICE', 's-001', 'Created IT service “Core Network” (critical criticality)'),
  a('a-002', 58, 10, AMARA, 'CREATE', 'SLA_POLICY', 'p-001', 'Created P1 SLA policy for Core Network: respond in 5 min, resolve in 2 h'),
  a('a-003', 52, 14, DANIEL, 'CREATE', 'CATEGORY', 'c-001', 'Created incident category “Network & Connectivity”'),
  a('a-004', 47, 11, DANIEL, 'UPDATE', 'CATEGORY', 'c-001', 'Updated incident category “Network & Connectivity”', { keywords: 'wifi, no internet, dns' }, { keywords: 'wifi, wi-fi, no internet, network down, dns, ethernet, slow network' }),
  a('a-005', 40, 9, AMARA, 'CREATE', 'USER', 'u-005', 'Invited Mei-Ling Chen as EMPLOYEE'),
  a('a-006', 33, 15, AMARA, 'UPDATE', 'SLA_POLICY', 'p-006', 'Updated P2 SLA policy for Identity & Single Sign-On: respond in 15 min, resolve in 6 h', { resolutionMinutes: '240' }, { resolutionMinutes: '360' }),
  a('a-007', 30, 10, DANIEL, 'UPDATE', 'SERVICE', 's-005', 'Updated IT service “Email & Calendar”', { dependsOn: 's-001' }, { dependsOn: 's-001, s-002' }),
  a('a-008', 27, 13, AMARA, 'STATUS_CHANGE', 'SERVICE', 's-011', 'Deactivated IT service “Legacy Timetable”', { status: 'ACTIVE' }, { status: 'INACTIVE' }),
  a('a-009', 27, 13, AMARA, 'STATUS_CHANGE', 'CATEGORY', 'c-011', 'Deactivated incident category “Legacy Timetabling”', { status: 'ACTIVE' }, { status: 'INACTIVE' }),
  a('a-010', 24, 9, DANIEL, 'CREATE', 'TECHNICIAN', 't-004', 'Created technician profile for Kofi Mensah'),
  a('a-011', 21, 16, AMARA, 'UPDATE', 'USER', 'u-009', 'Updated details for Elena Petrova', { department: 'Procurement' }, { department: 'Finance' }),
  a('a-012', 18, 11, DANIEL, 'UPDATE', 'TECHNICIAN', 't-003', 'Updated technician profile for Mei-Ling Chen', { skills: 'Email & Calendar, Identity & Access', maxWorkload: '6' }, { skills: 'Email & Calendar, Identity & Access, Cloud', maxWorkload: '8' }),
  a('a-013', 14, 10, AMARA, 'ROLE_CHANGE', 'USER', 'u-005', 'Changed role of Mei-Ling Chen from EMPLOYEE to TECHNICIAN', { role: 'EMPLOYEE' }, { role: 'TECHNICIAN' }),
  a('a-014', 12, 14, DANIEL, 'CREATE', 'CATEGORY', 'c-007', 'Created incident category “Security Incident”'),
  a('a-015', 10, 9, AMARA, 'UPDATE', 'SLA_POLICY', 'p-009', 'Updated P2 SLA policy for Learning Management System: respond in 30 min, resolve in 8 h', { responseMinutes: '45', resolutionMinutes: '720' }, { responseMinutes: '30', resolutionMinutes: '480' }),
  a('a-016', 9, 15, DANIEL, 'STATUS_CHANGE', 'USER', 'u-008', 'Suspended Rahul Verma', { status: 'ACTIVE' }, { status: 'SUSPENDED' }),
  a('a-017', 8, 11, DANIEL, 'DELETE', 'SLA_POLICY', 'p-099', 'Deleted P4 SLA policy for Print Services'),
  a('a-018', 7, 10, AMARA, 'STATUS_CHANGE', 'TECHNICIAN', 't-006', 'Set Rahul Verma to on leave', { availability: 'Available' }, { availability: 'On leave' }),
  a('a-019', 5, 11, AMARA, 'UPDATE', 'TECHNICIAN', 't-001', 'Updated technician profile for Priya Natarajan', { skills: 'Networking, VPN & Remote' }, { skills: 'Networking, VPN & Remote, Wi-Fi, Security' }),
  a('a-020', 4, 13, DANIEL, 'DELETE', 'USER', 'u-099', 'Deleted account of Former Contractor (former.contractor@campus.edu)'),
  a('a-021', 3, 9, AMARA, 'UPDATE', 'SERVICE', 's-006', 'Updated IT service “Learning Management System”', { criticality: 'High' }, { criticality: 'Critical' }),
  a('a-022', 2, 16, DANIEL, 'CREATE', 'USER', 'u-023', 'Invited Ben Adeyemi as EMPLOYEE'),
];
