import { DEMO_TECHNICIANS } from '../data/people';
import { buildWorld, type World } from '../data/seed';
import {
  applyTransition, availableTransitions, defaultVisibility, entryTypeFor, FEEDBACK_STATES, hasPermission, NOTE_STATES,
  TRANSITIONS, WORKLOAD_STATES, needsReporter,
} from '../model/lifecycle';
import {
  eligible, LEVEL_PROFICIENCY, LEVEL_YEARS, recommend, requireCanTake, type Candidate,
} from '../model/recommender';
import {
  IncidentError, type Actor, type ActorRole, type Audience, type Feedback, type Incident, type IncidentState, type Priority, type Recommendation,
  type SimilarResolution, type TimelineEntry, type TransitionName,
} from '../model/types';
import { loadWorld, saveWorld, simulate } from './store';
import { liveIncidentService } from './live-incident-service';
import { needsAttention, same } from './attention';
import { INCIDENT_PAGE_SIZE, TABS, type TabId } from './tabs';

export { TABS, defaultTab, INCIDENT_PAGE_SIZE, type TabId } from './tabs';

export interface AnalysisResult {
  suggestedCategoryId: number | null;
  suggestedCategoryName: string | null;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  reasoning: string;
  possibleDuplicates: { id: string; title: string; reason: string }[];
}

// ---- Visibility and access ----------------------------------------------------------------------------------------


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

// ---- Technician pool (this feature's own mock data, see data/people.ts) --------------------------------------------

function candidates(w: World): Candidate[] {
  return DEMO_TECHNICIANS.map((t) => {
    const skills = Object.fromEntries(t.skills.map((s) => [s, LEVEL_PROFICIENCY[t.skillLevel]]));
    const activeCount = w.incidents.filter((i) => same(i.assigneeEmail, t.email) && WORKLOAD_STATES.includes(i.state)).length;
    return {
      email: t.email, name: t.name, team: t.team, available: t.available,
      maxWorkload: t.maxWorkload, activeCount, experienceYears: LEVEL_YEARS[t.skillLevel], skills,
    };
  });
}

// ---- Read models --------------------------------------------------------------------------------------------------

export interface IncidentRow extends Incident {
  /** True when this person is the one who has to act next. */
  needsAttention: boolean;
}

/** One page of a tab. `page` is 1-based; `total` counts the whole tab (after the search), not just this page. */
export interface IncidentListResult {
  items: IncidentRow[];
  counts: Partial<Record<TabId, number>>;
  total: number;
  page: number;
  pageSize: number;
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

export const mockIncidentService = {
  async list(actor: Actor, tab: TabId, search = '', page = 1): Promise<IncidentListResult> {
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
      const pages = Math.max(1, Math.ceil(items.length / INCIDENT_PAGE_SIZE));
      const current = Math.min(Math.max(1, page), pages);
      return { items: items.slice((current - 1) * INCIDENT_PAGE_SIZE, current * INCIDENT_PAGE_SIZE), counts, total: items.length, page: current, pageSize: INCIDENT_PAGE_SIZE };
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

  async loadCatalog(): Promise<{ categories: { id: number; name: string }[]; services: { id: number; name: string }[] }> {
    return simulate(() => ({
      categories: MOCK_CATEGORIES,
      services: MOCK_SERVICES,
    }));
  },

  async create(
    actor: Actor,
    input: { title: string; description: string; categoryId: number; serviceId: number; priority: Priority }
  ): Promise<string> {
    return simulate(() => {
      const w = world(actor);
      const category = MOCK_CATEGORIES.find((c) => c.id === input.categoryId);
      const service = MOCK_SERVICES.find((s) => s.id === input.serviceId);
      if (!category) throw new IncidentError('VALIDATION', 'Unknown category.', { categoryId: 'Select a valid category.' });
      if (!service) throw new IncidentError('VALIDATION', 'Unknown service.', { serviceId: 'Select a valid service.' });
      const now = new Date().toISOString();
      const seq = w.incidents.length + 1001;
      const id = `INC${seq}`;
      w.incidents.push({
        id, title: input.title, description: input.description, category: category.name, service: service.name,
        priority: input.priority, state: 'CLASSIFIED', reporterEmail: actor.email, reporterName: actor.name,
        assigneeEmail: null, assigneeName: null, createdAt: now, updatedAt: now, resolvedAt: null,
      });
      saveWorld(actor.email, w);
      return id;
    });
  },

  async update(actor: Actor, id: string, input: { title: string; description: string }): Promise<void> {
    return simulate(() => {
      const w = world(actor);
      const i = find(w, actor, id);
      if (i.state !== 'NEW' && i.state !== 'CLASSIFIED') {
        throw new IncidentError('CONFLICT', 'Incident details can only be edited before it has been assigned.');
      }
      if (actor.role !== 'ADMIN' && !same(i.reporterEmail, actor.email)) {
        throw new IncidentError('FORBIDDEN', 'Only the reporter or an admin can edit incident details.');
      }
      i.title = input.title;
      i.description = input.description;
      i.updatedAt = new Date().toISOString();
      saveWorld(actor.email, w);
    });
  },

  async analyze(input: { title: string; description: string }): Promise<AnalysisResult> {
    return simulate(() => {
      const text = (input.title + ' ' + input.description).toLowerCase();
      let suggestedCategoryId = 6;
      let suggestedCategoryName = 'Other';
      if (/network|wifi|internet|connect|vpn|bandwidth/.test(text)) {
        suggestedCategoryId = 1; suggestedCategoryName = 'Network';
      } else if (/hardware|computer|monitor|keyboard|mouse|printer|device/.test(text)) {
        suggestedCategoryId = 2; suggestedCategoryName = 'Hardware';
      } else if (/software|app|crash|install|update|bug|error/.test(text)) {
        suggestedCategoryId = 3; suggestedCategoryName = 'Software';
      } else if (/security|breach|hack|virus|malware|phishing/.test(text)) {
        suggestedCategoryId = 4; suggestedCategoryName = 'Security';
      } else if (/access|login|password|account|permission|locked/.test(text)) {
        suggestedCategoryId = 5; suggestedCategoryName = 'Access & Identity';
      }
      return {
        suggestedCategoryId,
        suggestedCategoryName,
        confidence: 'MEDIUM' as const,
        reasoning: `The description contains terms related to ${suggestedCategoryName}.`,
        possibleDuplicates: [],
      };
    });
  },
};

const MOCK_CATEGORIES: { id: number; name: string }[] = [
  { id: 1, name: 'Network' }, { id: 2, name: 'Hardware' }, { id: 3, name: 'Software' },
  { id: 4, name: 'Security' }, { id: 5, name: 'Access & Identity' }, { id: 6, name: 'Other' },
];
const MOCK_SERVICES: { id: number; name: string }[] = [
  { id: 1, name: 'Email' }, { id: 2, name: 'VPN' }, { id: 3, name: 'Workstations' },
  { id: 4, name: 'File Storage' }, { id: 5, name: 'Internal Portal' }, { id: 6, name: 'Printers' },
];

/**
 * Which implementation the pages use, fixed at build time. Default is the mock (the demo and the e2e suite).
 * `NEXT_PUBLIC_INCIDENTS_API=live` talks to the backend: see `docs/architecture/p3.md` ("Frontend") for what it cannot do yet.
 */
export const incidentService: typeof mockIncidentService =
  process.env.NEXT_PUBLIC_INCIDENTS_API === 'live' ? liveIncidentService : mockIncidentService;
