/**
 * The people in the mock incident data: fictitious, on the reserved `campus.example` domain. This feature owns them (they
 * used to be read from the admin workspace's mock layer, which is gone now that the admin area talks to the real backend).
 */

export interface DemoPerson {
  email: string;
  name: string;
}

export type SkillLevel = 'JUNIOR' | 'INTERMEDIATE' | 'SENIOR';

/** A technician in the mock assignment pool. `available` already folds in shift, leave and suspension. */
export interface DemoTechnician extends DemoPerson {
  id: string;
  team: string;
  /** Skill names, matched against an incident's category name. */
  skills: string[];
  skillLevel: SkillLevel;
  available: boolean;
  maxWorkload: number;
}

export const DEMO_REPORTERS: Record<string, DemoPerson> = {
  'u-009': { email: 'elena.petrova@campus.example', name: 'Elena Petrova' },
  'u-010': { email: 'james.abara@campus.example', name: 'James Abara' },
  'u-011': { email: 'hannah.becker@campus.example', name: 'Hannah Becker' },
  'u-012': { email: 'luis.fernandez@campus.example', name: 'Luis Fernandez' },
  'u-013': { email: 'aisha.rahman@campus.example', name: 'Aisha Rahman' },
  'u-014': { email: 'oliver.grant@campus.example', name: 'Oliver Grant' },
  'u-015': { email: 'nadia.bashir@campus.example', name: 'Nadia Bashir' },
  'u-016': { email: 'victor.ng@campus.example', name: 'Victor Ng' },
  'u-017': { email: 'grace.mwangi@campus.example', name: 'Grace Mwangi' },
  'u-019': { email: 'yuki.tanaka@campus.example', name: 'Yuki Tanaka' },
  'u-021': { email: 'peter.novak@campus.example', name: 'Peter Novak' },
};

export const DEMO_TECHNICIANS: DemoTechnician[] = [
  { id: 't-001', email: 'priya.natarajan@campus.example', name: 'Priya Natarajan', team: 'Infrastructure', skills: ['Networking', 'VPN & Remote', 'Wi-Fi', 'Security'], skillLevel: 'SENIOR', available: true, maxWorkload: 8 },
  { id: 't-002', email: 'tomas.herrera@campus.example', name: 'Tomás Herrera', team: 'Service Desk', skills: ['Hardware', 'Windows', 'Printers'], skillLevel: 'INTERMEDIATE', available: false, maxWorkload: 6 },
  { id: 't-003', email: 'meiling.chen@campus.example', name: 'Mei-Ling Chen', team: 'Applications', skills: ['Email & Calendar', 'Identity & Access', 'Cloud'], skillLevel: 'SENIOR', available: true, maxWorkload: 8 },
  { id: 't-004', email: 'kofi.mensah@campus.example', name: 'Kofi Mensah', team: 'Service Desk', skills: ['macOS', 'Hardware', 'Audio/Visual'], skillLevel: 'INTERMEDIATE', available: true, maxWorkload: 6 },
  { id: 't-005', email: 'sara.lindqvist@campus.example', name: 'Sara Lindqvist', team: 'Applications', skills: ['Database', 'Linux', 'Cloud'], skillLevel: 'SENIOR', available: false, maxWorkload: 7 },
  { id: 't-006', email: 'rahul.verma@campus.example', name: 'Rahul Verma', team: 'Service Desk', skills: ['Printers', 'Windows'], skillLevel: 'JUNIOR', available: false, maxWorkload: 4 },
];

export const demoTechnician = (id: string): DemoPerson => {
  const t = DEMO_TECHNICIANS.find((x) => x.id === id)!;
  return { email: t.email, name: t.name };
};
