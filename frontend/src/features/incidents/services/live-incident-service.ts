import { ApiErrorResponse, request } from '@/lib/api';
import { FEEDBACK_STATES, hasPermission, NOTE_STATES, TRANSITIONS } from '../model/lifecycle';
import { IncidentError, type Actor, type Audience, type IncidentState, type Recommendation, type TransitionName } from '../model/types';
import type { AnalysisResult, Impact, IncidentDetail, IncidentListResult, Urgency } from './incident-service';
import { needsAttention } from './attention';
import { INCIDENT_PAGE_SIZE, TABS, type TabId } from './tabs';
import {
  liveId, toFeedback, toIncident, toIncidentError, toIncidentFromSummary, toRecommendation, toSimilar, toTimelineEntry,
  type ApiFeedback, type ApiIncident, type ApiIncidentList, type ApiRecommendation, type ApiResolved, type ApiTimelineEntry, type Me,
} from './incident-mappers';

/**
 * The incident service against the real backend (`NEXT_PUBLIC_INCIDENTS_API=live`). Same shape as the mock, so pages do
 * not change. Everything the backend has an endpoint for works; the rest is listed in docs/architecture/p3.md.
 * The backend decides what the caller may do, so `actions` come from it, not from the copy of the rules in `lifecycle.ts`.
 */
const ACTIVE: IncidentState[] = ['ASSIGNED', 'ACKNOWLEDGED', 'IN_PROGRESS', 'PENDING_INFO'];
const me = (a: Actor): Me => {
  if (a.id === undefined) throw new IncidentError('UNAVAILABLE', 'Your account could not be identified. Please sign in again.');
  return { id: a.id, email: a.email, name: a.name };
};

/**
 * Every call goes through the app's one HTTP client (`lib/api.ts`), so it authenticates however the app does (the session cookie and
 * the CSRF header today) without this file knowing. Its errors are turned into the ones the incident pages already handle.
 */
async function call<T>(method: 'GET' | 'POST' | 'PATCH', path: string, body?: unknown): Promise<T> {
  try {
    return await request<T>(`/api/incidents${path}`, { method, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    if (e instanceof ApiErrorResponse) {
      // `code` and `fieldErrors` exist on the client that parses problem+json; read them defensively.
      const rich = e as ApiErrorResponse & { code?: string; fieldErrors?: { field: string; message: string }[] };
      throw toIncidentError(e.status, { code: rich.code, detail: e.message, errors: rich.fieldErrors });
    }
    throw new IncidentError('UNAVAILABLE', 'The incident service is unreachable. Please try again.');
  }
}

const enc = (id: string) => encodeURIComponent(id);

export const liveIncidentService = {
  /**
   * One request per page of a tab. The backend scopes the list to what the caller may open and orders it by urgency, so nothing is
   * filtered here. `needsAttention` floats the incidents waiting on this person first within the page (the server cannot know it).
   */
  async list(actor: Actor, tab: TabId, search = '', page = 1): Promise<IncidentListResult> {
    const who = me(actor);
    const tabs = TABS[actor.role];
    const states = (tabs.find((t) => t.id === tab) ?? tabs[0]).states;
    const query = new URLSearchParams({ states: states.join(','), page: String(Math.max(1, page) - 1), size: String(INCIDENT_PAGE_SIZE) });
    if (search.trim()) query.set('q', search.trim());
    const page_ = await call<ApiIncidentList>('GET', `?${query}`);
    const counts = Object.fromEntries(tabs.map((t) => [t.id, t.states.reduce((n, s) => n + (page_.stateCounts[s] ?? 0), 0)])) as Partial<Record<TabId, number>>;
    const items = page_.items
      .map((d) => {
        const incident = toIncidentFromSummary(d, who);
        return { ...incident, needsAttention: needsAttention(actor, incident, d.hasFeedback) };
      })
      .sort((a, b) => Number(b.needsAttention) - Number(a.needsAttention)); // stable: the server order (urgency, newest) is kept within each group
    return { items, counts, total: page_.totalItems, page: Math.max(1, page), pageSize: INCIDENT_PAGE_SIZE };
  },

  async get(actor: Actor, id: string): Promise<IncidentDetail> {
    const who = me(actor);
    // The page recognises "not found" by the mock's wording, so say it the same way whatever the backend's text is.
    const dto = await call<ApiIncident>('GET', `/${enc(id)}`).catch((e: unknown) => {
      if (e instanceof IncidentError && e.code === 'NOT_FOUND') throw new IncidentError('NOT_FOUND', 'We couldn’t find that incident.');
      throw e;
    });
    const incident = toIncident(dto, who);
    const staff = hasPermission(actor.role, 'INCIDENT_WORK');
    const wantsSimilar = staff && ACTIVE.includes(dto.state);
    const [timeline, feedback, similar] = await Promise.all([
      call<ApiTimelineEntry[]>('GET', `/${enc(id)}/timeline`),
      call<ApiFeedback>('GET', `/${enc(id)}/feedback`).catch((e: unknown) => {
        if (e instanceof IncidentError && e.code === 'NOT_FOUND') return null; // no feedback yet is normal
        throw e;
      }),
      wantsSimilar ? call<ApiResolved[]>('GET', `/${enc(id)}/similar?limit=3`) : Promise.resolve([] as ApiResolved[]),
    ]);

    // The backend lists the moves it accepts on /transitions; assignment is a separate call, offered to admins here.
    const actions = dto.availableTransitions as TransitionName[];
    if (actor.role === 'ADMIN') {
      if (TRANSITIONS.ASSIGN.from.includes(dto.state)) actions.push('ASSIGN');
      else if (TRANSITIONS.REASSIGN.from.includes(dto.state)) actions.push('REASSIGN');
    }
    const owns = actor.role === 'ADMIN' || dto.assigneeId === who.id;
    return {
      incident,
      audience: (staff ? 'staff' : 'reporter') as Audience,
      timeline: timeline.map((e, i) => toTimelineEntry(e, who, id, i)),
      feedback: feedback ? toFeedback(feedback, who, id) : null,
      actions,
      canAddNote: staff && owns && NOTE_STATES.includes(dto.state),
      canGiveFeedback: dto.reporterId === who.id && hasPermission(actor.role, 'INCIDENT_VERIFY_OWN') && FEEDBACK_STATES.includes(dto.state) && !feedback,
      similar: similar.map(toSimilar),
    };
  },

  async perform(_actor: Actor, id: string, name: TransitionName, reason?: string): Promise<void> {
    if (name === 'ASSIGN' || name === 'REASSIGN' || name === 'CLASSIFY') throw new IncidentError('VALIDATION', 'That move is not available here.');
    await call('POST', `/${enc(id)}/transitions`, { transition: name, reason: reason ?? null });
  },

  async recommendations(_actor: Actor, id: string): Promise<Recommendation[]> {
    return (await call<ApiRecommendation[]>('GET', `/${enc(id)}/recommendations`)).map(toRecommendation);
  },

  async assign(_actor: Actor, id: string, technicianEmail: string, reason?: string): Promise<void> {
    const technicianId = liveId(technicianEmail);
    if (technicianId === null) throw new IncidentError('NOT_FOUND', 'That technician was not found.');
    await call('POST', `/${enc(id)}/assignment`, { technicianId, reason: reason ?? null });
  },

  async addNote(_actor: Actor, id: string, body: string, shareWithReporter: boolean): Promise<void> {
    await call('POST', `/${enc(id)}/timeline`, { type: 'NOTE', body, visibility: shareWithReporter ? 'REPORTER_VISIBLE' : 'INTERNAL' });
  },

  async submitFeedback(_actor: Actor, id: string, rating: number, comment: string): Promise<void> {
    await call('POST', `/${enc(id)}/feedback`, { rating, comment: comment.trim() || null });
  },

  async loadCatalog(): Promise<{ categories: { id: number; name: string }[]; services: { id: number; name: string }[] }> {
    const [categories, services] = await Promise.all([
      call<{ id: number; name: string }[]>('GET', '/catalog/categories'),
      call<{ id: number; name: string }[]>('GET', '/catalog/services'),
    ]);
    return { categories, services };
  },

  async create(
    _actor: Actor,
    input: { title: string; description: string; categoryId: number; serviceId: number; impact: Impact; urgency: Urgency }
  ): Promise<string> {
    const dto = await call<{ id: string }>('POST', '', input);
    return dto.id;
  },

  async update(_actor: Actor, id: string, input: { title: string; description: string }): Promise<void> {
    await call('PATCH', `/${enc(id)}`, input);
  },

  async analyze(input: { title: string; description: string }): Promise<AnalysisResult> {
    return call<AnalysisResult>('POST', '/analyze', input);
  },
};
