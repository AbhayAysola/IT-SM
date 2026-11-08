import type { IncidentCategory, ItService, Priority, SlaPolicy } from '../types';

const at = (daysAgo: number) => {
  const t = new Date('2026-11-06T09:30:00Z');
  t.setUTCDate(t.getUTCDate() - daysAgo);
  return t.toISOString();
};

const cat = (id: string, name: string, description: string, keywords: string[], defaultPriority: Priority, status: 'ACTIVE' | 'INACTIVE', days: number): IncidentCategory =>
  ({ id, name, description, keywords, defaultPriority, status, updatedAt: at(days) });

export const SEED_CATEGORIES: IncidentCategory[] = [
  cat('c-001', 'Network & Connectivity', 'Loss of wired or wireless connectivity, slow networks and DNS problems.', ['wifi', 'wi-fi', 'no internet', 'network down', 'dns', 'ethernet', 'slow network'], 'P2', 'ACTIVE', 30),
  cat('c-002', 'Email & Calendar', 'Mailbox access, sending and receiving, calendar sync and shared mailboxes.', ['email', 'outlook', 'mailbox', 'calendar', 'cannot send', 'shared mailbox'], 'P3', 'ACTIVE', 28),
  cat('c-003', 'Accounts & Access', 'Password resets, locked accounts, multi-factor prompts and permission requests.', ['password', 'locked out', 'login', 'mfa', 'access denied', 'permission'], 'P3', 'ACTIVE', 25),
  cat('c-004', 'Hardware', 'Faulty laptops, desktops, monitors and peripherals.', ['laptop', 'monitor', 'keyboard', 'mouse', 'battery', 'screen broken'], 'P3', 'ACTIVE', 22),
  cat('c-005', 'Software & Applications', 'Installation, crashes and licensing of desktop and campus applications.', ['install', 'crash', 'license', 'application error', 'update failed'], 'P3', 'ACTIVE', 20),
  cat('c-006', 'Printing & Scanning', 'Printers, copiers and scanners, including queues and toner.', ['printer', 'print queue', 'toner', 'scanner', 'paper jam'], 'P4', 'ACTIVE', 18),
  cat('c-007', 'Security Incident', 'Suspected phishing, malware, lost devices and data exposure.', ['phishing', 'malware', 'virus', 'ransomware', 'lost device', 'data breach'], 'P1', 'ACTIVE', 12),
  cat('c-008', 'Learning Platform', 'Problems with the learning management system and online assessments.', ['lms', 'moodle', 'online exam', 'course site', 'gradebook'], 'P2', 'ACTIVE', 9),
  cat('c-009', 'Audio/Visual & Classrooms', 'Projectors, lecture capture and classroom equipment.', ['projector', 'lecture capture', 'classroom', 'microphone', 'hdmi'], 'P3', 'ACTIVE', 7),
  cat('c-010', 'Remote Access & VPN', 'VPN connection failures and remote desktop issues.', ['vpn', 'remote desktop', 'cannot connect remotely'], 'P3', 'ACTIVE', 6),
  cat('c-011', 'Legacy Timetabling', 'Retired timetable system, kept for historical tickets.', ['timetable', 'room booking legacy'], 'P4', 'INACTIVE', 80),
];

const svc = (id: string, name: string, description: string, ownerTeam: string, criticality: ItService['criticality'], dependsOn: string[], status: 'ACTIVE' | 'INACTIVE', days: number): ItService =>
  ({ id, name, description, ownerTeam, criticality, dependsOn, status, updatedAt: at(days) });

export const SEED_SERVICES: ItService[] = [
  svc('s-001', 'Core Network', 'Campus backbone, switching, routing and DNS/DHCP.', 'Infrastructure', 'CRITICAL', [], 'ACTIVE', 40),
  svc('s-002', 'Identity & Single Sign-On', 'Staff and student accounts, multi-factor authentication and SSO.', 'Security', 'CRITICAL', ['s-001'], 'ACTIVE', 35),
  svc('s-003', 'Campus Wi-Fi', 'Managed wireless network in teaching and residential buildings.', 'Infrastructure', 'HIGH', ['s-001'], 'ACTIVE', 33),
  svc('s-004', 'Database Platform', 'Shared database clusters behind campus applications.', 'Applications', 'HIGH', ['s-001'], 'ACTIVE', 30),
  svc('s-005', 'Email & Calendar', 'Hosted mail, calendars and shared mailboxes.', 'Applications', 'HIGH', ['s-001', 's-002'], 'ACTIVE', 27),
  svc('s-006', 'Learning Management System', 'Course sites, assignments and online assessments.', 'Applications', 'CRITICAL', ['s-002', 's-004'], 'ACTIVE', 21),
  svc('s-007', 'Student Information System', 'Enrolment, grades and student records.', 'Applications', 'CRITICAL', ['s-002', 's-004'], 'ACTIVE', 19),
  svc('s-008', 'VPN & Remote Access', 'Secure remote access for staff working off campus.', 'Infrastructure', 'MEDIUM', ['s-001', 's-002'], 'ACTIVE', 15),
  svc('s-009', 'Print Services', 'Managed print queues, release stations and copiers.', 'Service Desk', 'LOW', ['s-001'], 'ACTIVE', 11),
  svc('s-010', 'Library Catalogue', 'Search and loan services for the library collection.', 'Applications', 'MEDIUM', ['s-004'], 'ACTIVE', 8),
  svc('s-011', 'Legacy Timetable', 'Retired timetabling system kept read-only.', 'Applications', 'LOW', [], 'INACTIVE', 90),
];

const pol = (id: string, serviceId: string, priority: Priority, response: number, resolution: number, status: 'ACTIVE' | 'INACTIVE' = 'ACTIVE'): SlaPolicy =>
  ({ id, serviceId, priority, responseMinutes: response, resolutionMinutes: resolution, status, updatedAt: at(14) });

export const SEED_SLA_POLICIES: SlaPolicy[] = [
  pol('p-001', 's-001', 'P1', 5, 120), pol('p-002', 's-001', 'P2', 15, 240), pol('p-003', 's-001', 'P3', 60, 1440), pol('p-004', 's-001', 'P4', 240, 4320),
  pol('p-005', 's-002', 'P1', 5, 120), pol('p-006', 's-002', 'P2', 15, 360), pol('p-007', 's-002', 'P3', 60, 1440),
  pol('p-008', 's-006', 'P1', 10, 180), pol('p-009', 's-006', 'P2', 30, 480), pol('p-010', 's-006', 'P3', 120, 1440),
  pol('p-011', 's-003', 'P1', 15, 240), pol('p-012', 's-003', 'P2', 30, 480), pol('p-013', 's-003', 'P3', 120, 1440), pol('p-014', 's-003', 'P4', 480, 4320),
  pol('p-015', 's-005', 'P2', 30, 480), pol('p-016', 's-005', 'P3', 120, 1440),
  pol('p-017', 's-009', 'P3', 120, 2880, 'INACTIVE'),
];
