import { ApiErrorResponse, request } from '@/lib/api';
import { FEEDBACK_STATES, hasPermission, NOTE_STATES, TRANSITIONS } from '../model/lifecycle';
import { IncidentError, type Actor, type Audience, type IncidentState, type Priority, type Recommendation, type TransitionName } from '../model/types';
import type { AnalysisResult, FilterOptions, ChatMessage, ChatTurn, Impact, IncidentDetail, IncidentListResult, Urgency } from './incident-service';
import { needsAttention } from './attention';
import type { PrioritySuggestionView } from '../model/priority';
import { SLA_QUEUE_SIZE, type SlaQueueItem } from '../model/sla';
import { technicianApi } from '@/features/admin/services/technician-api';
import { INCIDENT_PAGE_SIZE, TABS, type IncidentFilters, type IncidentSort, type TabId } from './tabs';
import {
  toFeedback, toIncident, toIncidentError, toIncidentFromSummary, toQueueItem, toRecommendation, toEscalation, toHistoryEntry, toRowSla, toSimilar, toSla, toTimelineEntry,
  type ApiEscalation, type ApiFeedback, type ApiHistoryEntry, type ApiSla, type ApiIncident, type ApiIncidentList, type ApiRecommendation, type ApiResolved, type ApiTimelineEntry, type Me,
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
  async list(actor: Actor, tab: TabId, search = '', page = 1, filters: IncidentFilters = {}, sort: IncidentSort = 'URGENCY'): Promise<IncidentListResult> {
    const who = me(actor);
    const tabs = TABS[actor.role];
    const states = (tabs.find((t) => t.id === tab) ?? tabs[0]).states;
    const query = new URLSearchParams({ states: states.join(','), page: String(Math.max(1, page) - 1), size: String(INCIDENT_PAGE_SIZE) });
    if (search.trim()) query.set('q', search.trim());
    if (filters.priority) query.set('priority', filters.priority);
    if (filters.categoryId !== undefined) query.set('categoryId', String(filters.categoryId));
    if (filters.serviceId !== undefined) query.set('serviceId', String(filters.serviceId));
    if (filters.assigneeId !== undefined) query.set('assigneeId', String(filters.assigneeId));
    if (filters.slaStatus) query.set('slaStatus', filters.slaStatus);
    if (filters.predictedMiss) query.set('predictedMiss', 'true');
    if (sort !== 'URGENCY') query.set('sort', sort);
    if (filters.from) query.set('createdFrom', filters.from);
    if (filters.to) query.set('createdTo', filters.to);
    const page_ = await call<ApiIncidentList>('GET', `?${query}`);
    const counts = Object.fromEntries(tabs.map((t) => [t.id, t.states.reduce((n, s) => n + (page_.stateCounts[s] ?? 0), 0)])) as Partial<Record<TabId, number>>;
    const items = page_.items
      .map((d) => {
        const incident = toIncidentFromSummary(d, who);
        return { ...incident, needsAttention: needsAttention(actor, incident, d.hasFeedback), sla: toRowSla(d.sla) };
      });
    // Stable: the server order (urgency, newest) is kept within each group. A deadline order is kept as the server sent it.
    if (sort === 'URGENCY') items.sort((a, b) => Number(b.needsAttention) - Number(a.needsAttention));
    const attention = page_.slaAttention ? { breached: page_.slaAttention.breached, atRisk: page_.slaAttention.atRisk, predictedMiss: page_.slaAttention.predictedMiss } : null;
    return { items, counts, total: page_.totalItems, page: Math.max(1, page), pageSize: INCIDENT_PAGE_SIZE, attention };
  },

  async myWorkload(_actor: Actor): Promise<{ active: number; maxWorkload: number }> {
    return call<{ active: number; maxWorkload: number }>('GET', '/workload/me');
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
    const [timeline, feedback, similar, sla, escalations, history] = await Promise.all([
      call<ApiTimelineEntry[]>('GET', `/${enc(id)}/timeline`),
      // Only ask when there is something to read, so an unrated incident leaves no 404 in the console.
      dto.hasFeedback === false ? Promise.resolve(null) : call<ApiFeedback>('GET', `/${enc(id)}/feedback`).catch((e: unknown) => {
        if (e instanceof IncidentError && e.code === 'NOT_FOUND') return null; // no feedback yet is normal (older backends)
        throw e;
      }),
      wantsSimilar ? call<ApiResolved[]>('GET', `/${enc(id)}/similar?limit=3`) : Promise.resolve([] as ApiResolved[]),
      // P4's SLA is extra information: if it cannot be loaded the page still works, without the panel.
      call<ApiSla>('GET', `/${enc(id)}/sla`).then(toSla, () => null),
      // Escalations name the people involved, so the backend shows them to staff only; also extra information.
      staff ? call<ApiEscalation[]>('GET', `/${enc(id)}/escalations`).then((l) => l.map(toEscalation), () => []) : Promise.resolve([]),
      // The audit trail is for staff: it records system steps (SLA clocks, AI flags, escalations) a reporter has no use for.
      // It is extra information too (it answers 503 while the access policy is not deployed), so a failure hides the card only.
      staff
        ? call<{ items: ApiHistoryEntry[] }>('GET', `/${enc(id)}/audit?size=100`).then((p) => p.items.map(toHistoryEntry), () => null)
        : Promise.resolve(null),
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
      sla,
      escalations,
      history,
    };
  },

  async perform(_actor: Actor, id: string, name: TransitionName, reason?: string): Promise<void> {
    if (name === 'ASSIGN' || name === 'REASSIGN' || name === 'CLASSIFY') throw new IncidentError('VALIDATION', 'That move is not available here.');
    await call('POST', `/${enc(id)}/transitions`, { transition: name, reason: reason ?? null });
  },

  async recommendations(_actor: Actor, id: string): Promise<Recommendation[]> {
    return (await call<ApiRecommendation[]>('GET', `/${enc(id)}/recommendations`)).map(toRecommendation);
  },

  async assign(_actor: Actor, id: string, technicianId: number, reason?: string): Promise<void> {
    await call('POST', `/${enc(id)}/assignment`, { technicianId, reason: reason ?? null });
  },

  /** P4's SLA queue (`GET /api/sla/incidents`): technicians get their own incidents, admins everything. */
  async slaQueue(_actor: Actor): Promise<SlaQueueItem[]> {
    const q = new URLSearchParams({ open: 'true', unresolved: 'true', size: String(SLA_QUEUE_SIZE) });
    for (const s of ['BREACHED', 'WARNING', 'RUNNING']) q.append('status', s);
    try {
      const page = await request<{ items: ApiSla[] }>(`/api/sla/incidents?${q}`);
      return page.items.map(toQueueItem);
    } catch (e) {
      if (e instanceof ApiErrorResponse) throw toIncidentError(e.status, { detail: e.message });
      throw new IncidentError('UNAVAILABLE', 'The SLA service is unreachable. Please try again.');
    }
  },

  /** P4's open suggestion, or null (204). Admins only. */
  async prioritySuggestion(_actor: Actor, id: string): Promise<PrioritySuggestionView | null> {
    const d = await call<Partial<PrioritySuggestionView>>('GET', `/${enc(id)}/priority-suggestion`);
    if (!d.suggestedPriority || !d.currentPriority) return null;
    return { currentPriority: d.currentPriority, suggestedPriority: d.suggestedPriority, rules: d.rules ?? [], reasons: d.reasons ?? [], createdAt: d.createdAt ?? '' };
  },

  async dismissPrioritySuggestion(_actor: Actor, id: string): Promise<void> {
    await call('POST', `/${enc(id)}/priority-suggestion/dismiss`);
  },

  /** P2's `POST /api/incidents/{id}/priority` (admins). */
  async changePriority(_actor: Actor, id: string, priority: Priority, reason: string): Promise<void> {
    await call('POST', `/${enc(id)}/priority`, { priority, reason: reason.trim() });
  },

  async addNote(_actor: Actor, id: string, body: string, shareWithReporter: boolean): Promise<void> {
    await call('POST', `/${enc(id)}/timeline`, { type: 'NOTE', body, visibility: shareWithReporter ? 'REPORTER_VISIBLE' : 'INTERNAL' });
  },

  async submitFeedback(_actor: Actor, id: string, rating: number, comment: string): Promise<void> {
    await call('POST', `/${enc(id)}/feedback`, { rating, comment: comment.trim() || null });
  },

  /** The categories and services the backend knows, plus (admins only) the technicians: the list filters use ids. */
  async loadFilterOptions(actor: Actor): Promise<FilterOptions> {
    const catalog = await liveIncidentService.loadCatalog();
    if (actor.role !== 'ADMIN') return { ...catalog, technicians: [] };
    // Inactive technicians stay in the list: old incidents are still filed under them. A failure only hides this one dropdown.
    const technicians = await technicianApi.list({ active: 'ALL', sort: 'name', dir: 'asc', size: 100 })
      .then((p) => p.items.map((t) => ({ id: t.userId, name: t.name })))
      .catch(() => []);
    return { ...catalog, technicians };
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
    input: { title: string; description: string; categoryId: number; serviceId: number; impact: Impact; urgency: Urgency; aiSuggestedCategoryId?: number | null; aiDuplicateIds?: string[] }
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

  async chat(messages: ChatMessage[]): Promise<ChatTurn> {
    return call<ChatTurn>('POST', '/chat', { messages });
  },
};
