import { SEED_TECHNICIANS, SEED_USERS } from '@/features/admin/data/seed';
import { loadCollection } from '@/features/admin/services/store';
import type { ManagedUser, TechnicianProfile } from '@/features/admin/types';
import { buildWorld, type World } from '../data/seed';
import {
  applyTransition, availableTransitions, defaultVisibility, entryTypeFor, FEEDBACK_STATES, hasPermission, NOTE_STATES,
  TRANSITIONS, WORKLOAD_STATES, needsReporter,
} from '../model/lifecycle';
import {
  eligible, LEVEL_PROFICIENCY, LEVEL_YEARS, recommend, requireCanTake, type Candidate,
} from '../model/recommender';
import {
  IncidentError, type Actor, type ActorRole, type Audience, type Feedback, type Incident, type IncidentState, type Recommendation,
  type SimilarResolution, type TimelineEntry, type TransitionName,
} from '../model/types';
import { loadWorld, saveWorld, simulate } from './store';

// ---- Tabs ---------------------------------------------------------------------------------------------------------

export type TabId = 'ACTIVE' | 'WAITING' | 'DONE' | 'UNASSIGNED' | 'OPEN';

export const TABS: Record<ActorRole, { id: TabId; label: string; states: IncidentState[] }[]> = {
  TECHNICIAN: [
    { id: 'ACTIVE', label: 'Active', states: ['ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS'] },
    { id: 'WAITING', label: 'Waiting on reporter', states: ['PENDING_INFO', 'RESOLVED'] },
    { id: 'DONE', label: 'Done', states: ['VERIFIED', 'CLOSED'] },
  ],
  ADMIN: [
    { id: 'UNASSIGNED', label: 'Awaiting assignment', states: ['NEW', 'CLASSIFIED'] },
    { id: 'ACTIVE', label: 'In progress', states: ['ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO', 'RESOLVED'] },
    { id: 'DONE', label: 'Done', states: ['VERIFIED', 'CLOSED'] },
  ],
  EMPLOYEE: [
    { id: 'OPEN', label: 'Open', states: ['NEW', 'CLASSIFIED', 'ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO', 'RESOLVED'] },
    { id: 'DONE', label: 'Done', states: ['VERIFIED', 'CLOSED'] },
  ],
};

export const defaultTab = (role: ActorRole): TabId => TABS[role][0].id;

// ---- Visibility and access ----------------------------------------------------------------------------------------

const same = (a: string | null | undefined, b: string) => !!a && a.toLowerCase() === b.toLowerCase();

/** Admins see everything; others only what they are assigned or reported. Anything else behaves as "not found". */
const canOpen = (actor: Actor, i: Incident) =>
  actor.role === 'ADMIN' || same(i.assigneeEmail, actor.email) || same(i.reporterEmail, actor.email);

export const audienceFor = (actor: Actor): Audience => (hasPermission(actor.role, 'INCIDENT_WORK') ? 'staff' : 'reporter');

const world = (actor: Actor): World => loadWorld(actor.email, () => buildWorld(actor));

function find(w: World, actor: Actor, id: string): Incident {
  const i = w.incidents.find((x) => x.id === id);
  if (!i || !canOpen(actor, i)) throw new IncidentError('NOT_FOUND', 'We couldn’t find that incident.');
  return i;
}

const nextEntryId = (w: World) => `e-${w.timeline.length + 1}-${Date.now().toString(36)}`;

// ---- Technician pool (shared with the admin workspace's mock data) ---------------------------------------------

function candidates(w: World): Candidate[] {
  const profiles = loadCollection<TechnicianProfile>('technicians', SEED_TECHNICIANS);
  const users = loadCollection<ManagedUser>('users', SEED_USERS);
  return profiles.flatMap((p) => {
    const u = users.find((x) => x.id === p.userId);
    if (!u) return [];
    const skills = Object.fromEntries(p.skills.map((s) => [s, LEVEL_PROFICIENCY[p.skillLevel]]));
    const activeCount = w.incidents.filter((i) => same(i.assigneeEmail, u.email) && WORKLOAD_STATES.includes(i.state)).length;
    return [{
      email: u.email, name: u.name, team: p.team, available: p.availability === 'AVAILABLE' && u.status === 'ACTIVE',
      maxWorkload: p.maxWorkload, activeCount, experienceYears: LEVEL_YEARS[p.skillLevel], skills,
    }];
  });
}

// ---- Read models --------------------------------------------------------------------------------------------------

export interface IncidentRow extends Incident {
  /** True when this person is the one who has to act next. */
  needsAttention: boolean;
}

export interface IncidentDetail {
  incident: Incident;
  audience: Audience;
  timeline: TimelineEntry[];
  feedback: Feedback | null;
  actions: TransitionName[];
  canAddNote: boolean;
  canGiveFeedback: boolean;
  similar: SimilarResolution[];
}

function needsAttention(actor: Actor, i: Incident, hasFeedback: boolean): boolean {
  if (actor.role === 'ADMIN') return i.state === 'CLASSIFIED' || i.state === 'NEW';
  if (hasPermission(actor.role, 'INCIDENT_WORK') && same(i.assigneeEmail, actor.email)) return i.state === 'ASSIGNED' || i.state === 'ACKNOWLEDGED';
  if (same(i.reporterEmail, actor.email)) return needsReporter(i.state) || (FEEDBACK_STATES.includes(i.state) && !hasFeedback && hasPermission(actor.role, 'INCIDENT_VERIFY_OWN'));
  return false;
}

const PRIORITY_ORDER = { P1: 1, P2: 2, P3: 3, P4: 4 } as const;

function similarResolutions(w: World, i: Incident): SimilarResolution[] {
  return w.incidents
    .filter((o) => o.id !== i.id && o.category === i.category && (o.state === 'CLOSED' || o.state === 'VERIFIED') && o.resolvedAt)
    .flatMap((o) => {
      const res = [...w.timeline].reverse().find((e) => e.incidentId === o.id && e.type === 'RESOLUTION');
      if (!res) return [];
      const days = Math.max(0, Math.round((Date.parse(o.resolvedAt!) - Date.parse(o.createdAt)) / 86_400_000));
      return [{ incidentId: o.id, title: o.title, priority: o.priority, sameService: o.service === i.service, resolution: res.body, resolvedBy: res.authorName, daysToResolve: days, at: o.resolvedAt! }];
    })
    .sort((a, b) => Number(b.sameService) - Number(a.sameService) || Date.parse(b.at) - Date.parse(a.at))
    .slice(0, 3)
    .map(({ at: _at, ...rest }) => rest);
}

function validateText(value: string, field: string, label: string, max: number, required: boolean): string | null {
  const t = value.trim();
  if (!t) {
    if (required) throw new IncidentError('VALIDATION', 'Please write something first.', { [field]: `${label} is required.` });
    return null;
  }
  if (t.length > max) throw new IncidentError('VALIDATION', `Keep ${label.toLowerCase()} under ${max} characters.`, { [field]: `At most ${max} characters.` });
  return t;
}

export const NOTE_MAX = 4000;
export const COMMENT_MAX = 1000;

// ---- The service --------------------------------------------------------------------------------------------------

export const incidentService = {
  async list(actor: Actor, tab: TabId, search = ''): Promise<{ items: IncidentRow[]; counts: Partial<Record<TabId, number>> }> {
    return simulate(() => {
      const w = world(actor);
      const mine = w.incidents.filter((i) => canOpen(actor, i));
      const q = search.trim().toLowerCase();
      const hasFb = (id: string) => w.feedback.some((f) => f.incidentId === id);
      const tabs = TABS[actor.role];
      const counts = Object.fromEntries(tabs.map((t) => [t.id, mine.filter((i) => t.states.includes(i.state)).length])) as Partial<Record<TabId, number>>;
      const states = (tabs.find((t) => t.id === tab) ?? tabs[0]).states;
      const items = mine
        .filter((i) => states.includes(i.state))
        .filter((i) => !q || [i.id, i.title, i.category, i.service, i.reporterName, i.assigneeName ?? ''].some((s) => s.toLowerCase().includes(q)))
        .map((i) => ({ ...i, needsAttention: needsAttention(actor, i, hasFb(i.id)) }))
        .sort((a, b) => Number(b.needsAttention) - Number(a.needsAttention) || PRIORITY_ORDER[a.priority] - PRIORITY_ORDER[b.priority] || Date.parse(b.updatedAt) - Date.parse(a.updatedAt));
      return { items, counts };
    });
  },

  async get(actor: Actor, id: string): Promise<IncidentDetail> {
    return simulate(() => {
      const w = world(actor);
      const incident = find(w, actor, id);
      const staff = hasPermission(actor.role, 'INCIDENT_WORK');
      const feedback = w.feedback.find((f) => f.incidentId === id) ?? null;
      const timeline = w.timeline
        .filter((e) => e.incidentId === id && (staff || e.visibility === 'REPORTER_VISIBLE'))
        .sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt));
      const owns = actor.role === 'ADMIN' || same(incident.assigneeEmail, actor.email);
      const active: IncidentState[] = ['ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO'];
      return {
        incident,
        audience: audienceFor(actor),
        timeline,
        feedback,
        actions: availableTransitions(incident.state, actor, incident),
        canAddNote: staff && owns && NOTE_STATES.includes(incident.state),
        canGiveFeedback: same(incident.reporterEmail, actor.email) && hasPermission(actor.role, 'INCIDENT_VERIFY_OWN') && FEEDBACK_STATES.includes(incident.state) && !feedback,
        similar: staff && active.includes(incident.state) ? similarResolutions(w, incident) : [],
      };
    });
  },

  /** Any move except assignment (use `assign`). Validated exactly like the backend state machine. */
  async perform(actor: Actor, id: string, name: TransitionName, reason?: string): Promise<void> {
    return simulate(() => {
      if (name === 'ASSIGN' || name === 'REASSIGN' || name === 'CLASSIFY') {
        throw new IncidentError('VALIDATION', 'That move is not available here.');
      }
      const w = world(actor);
      const i = find(w, actor, id);
      const { to, reason: cleaned } = applyTransition(i.state, name, actor, i, reason);
      const now = new Date().toISOString();
      const entryType = entryTypeFor(name);
      if (entryType && cleaned) {
        w.timeline.push({
          id: nextEntryId(w), incidentId: id, authorEmail: actor.email, authorName: actor.name, type: entryType,
          visibility: defaultVisibility(entryType), body: cleaned, incidentState: i.state, createdAt: now,
        });
      }
      i.state = to;
      i.updatedAt = now;
      if (name === 'RESOLVE') i.resolvedAt = now;
      if (name === 'REOPEN') i.resolvedAt = null;
      saveWorld(actor.email, w);
    });
  },

  /** Admin only. Ranked, explained suggestions for who should take the incident. */
  async recommendations(actor: Actor, id: string): Promise<Recommendation[]> {
    return simulate(() => {
      if (!hasPermission(actor.role, 'INCIDENT_ASSIGN')) throw new IncidentError('FORBIDDEN', 'Only administrators can assign incidents.');
      const w = world(actor);
      const i = find(w, actor, id);
      if (!TRANSITIONS.ASSIGN.from.includes(i.state) && !TRANSITIONS.REASSIGN.from.includes(i.state)) {
        throw new IncidentError('CONFLICT', 'This incident can no longer be assigned.');
      }
      return recommend(eligible(candidates(w), i.assigneeEmail ? [i.assigneeEmail] : []), i.category, i.priority);
    });
  },

  /** ASSIGN from CLASSIFIED, REASSIGN (reason required) from an active state. */
  async assign(actor: Actor, id: string, technicianEmail: string, reason?: string): Promise<void> {
    return simulate(() => {
      const w = world(actor);
      const i = find(w, actor, id);
      const name: TransitionName = i.state === 'CLASSIFIED' ? 'ASSIGN' : 'REASSIGN';
      const { to, reason: cleaned } = applyTransition(i.state, name, actor, i, reason);
      const cand = candidates(w).find((c) => same(c.email, technicianEmail));
      if (!cand) throw new IncidentError('NOT_FOUND', 'That technician was not found.');
      if (same(i.assigneeEmail, technicianEmail)) throw new IncidentError('CONFLICT', `${cand.name} already has this incident.`);
      requireCanTake(cand);
      const now = new Date().toISOString();
      if (name === 'REASSIGN' && cleaned) {
        w.timeline.push({
          id: nextEntryId(w), incidentId: id, authorEmail: actor.email, authorName: actor.name, type: 'REASSIGNMENT',
          visibility: 'INTERNAL', body: cleaned, incidentState: i.state, createdAt: now,
        });
      }
      i.assigneeEmail = cand.email;
      i.assigneeName = cand.name;
      i.state = to;
      i.updatedAt = now;
      saveWorld(actor.email, w);
    });
  },

  /** A technician's own note. Order of checks mirrors the backend: input, then state, then permission. */
  async addNote(actor: Actor, id: string, body: string, shareWithReporter: boolean): Promise<void> {
    return simulate(() => {
      const text = validateText(body, 'body', 'The note', NOTE_MAX, true)!;
      const w = world(actor);
      const i = find(w, actor, id);
      if (!NOTE_STATES.includes(i.state)) throw new IncidentError('CONFLICT', 'Notes can only be added while the incident is being worked on.');
      if (!hasPermission(actor.role, 'INCIDENT_WORK') || !(actor.role === 'ADMIN' || same(i.assigneeEmail, actor.email))) {
        throw new IncidentError('FORBIDDEN', 'Only the assigned technician can add notes.');
      }
      w.timeline.push({
        id: nextEntryId(w), incidentId: id, authorEmail: actor.email, authorName: actor.name, type: 'NOTE',
        visibility: shareWithReporter ? 'REPORTER_VISIBLE' : 'INTERNAL', body: text, incidentState: i.state, createdAt: new Date().toISOString(),
      });
      i.updatedAt = new Date().toISOString();
      saveWorld(actor.email, w);
    });
  },

  /** Reporter only, once the fix is confirmed, once per incident. Order: who, state, duplicate, input. */
  async submitFeedback(actor: Actor, id: string, rating: number, comment: string): Promise<void> {
    return simulate(() => {
      const w = world(actor);
      const i = find(w, actor, id);
      if (!same(i.reporterEmail, actor.email) || !hasPermission(actor.role, 'INCIDENT_VERIFY_OWN')) {
        throw new IncidentError('FORBIDDEN', 'Only the person who reported the incident can give feedback.');
      }
      if (!FEEDBACK_STATES.includes(i.state)) throw new IncidentError('CONFLICT', 'You can give feedback once you have confirmed the fix.');
      if (w.feedback.some((f) => f.incidentId === id)) throw new IncidentError('CONFLICT', 'You have already given feedback on this incident.');
      const fields: Record<string, string> = {};
      if (!Number.isInteger(rating) || rating < 1 || rating > 5) fields.rating = 'Choose a rating from 1 to 5.';
      const text = comment.trim() || null;
      if (text && text.length > COMMENT_MAX) fields.comment = `At most ${COMMENT_MAX} characters.`;
      if (Object.keys(fields).length) throw new IncidentError('VALIDATION', 'Please correct the highlighted fields.', fields);
      w.feedback.push({ incidentId: id, reporterEmail: actor.email, rating, comment: text, submittedAt: new Date().toISOString() });
      saveWorld(actor.email, w);
    });
  },
};
