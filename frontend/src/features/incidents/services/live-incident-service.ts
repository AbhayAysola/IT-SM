import { API_URL } from '@/lib/api';
import { getToken } from '@/lib/auth';
import { FEEDBACK_STATES, hasPermission, NOTE_STATES, TRANSITIONS } from '../model/lifecycle';
import { IncidentError, type Actor, type Audience, type IncidentState, type Recommendation, type TransitionName } from '../model/types';
import type { IncidentDetail, IncidentRow, TabId } from './incident-service';
import {
  liveId, toFeedback, toIncident, toIncidentError, toRecommendation, toSimilar, toTimelineEntry,
  type ApiFeedback, type ApiIncident, type ApiRecommendation, type ApiResolved, type ApiTimelineEntry, type Me,
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

async function call<T>(method: 'GET' | 'POST', path: string, body?: unknown): Promise<T> {
  const token = getToken();
  let res: Response;
  try {
    res = await fetch(`${API_URL}/api/incidents${path}`, {
      method,
      headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new IncidentError('UNAVAILABLE', 'The incident service is unreachable. Please try again.');
  }
  if (!res.ok) throw toIncidentError(res.status, await res.json().catch(() => null));
  return (res.status === 204 ? undefined : await res.json()) as T;
}

const enc = (id: string) => encodeURIComponent(id);

export const liveIncidentService = {
  /** There is no list endpoint yet (TODO in p3.md). Open an incident directly: /incidents/INC1001. */
  async list(_actor: Actor, _tab: TabId, _search = ''): Promise<{ items: IncidentRow[]; counts: Partial<Record<TabId, number>> }> {
    throw new IncidentError('UNAVAILABLE', 'Listing incidents needs GET /api/incidents, which the backend does not have yet. Open an incident by its address, for example /incidents/INC1001.');
  },

  async get(actor: Actor, id: string): Promise<IncidentDetail> {
    const who = me(actor);
    const dto = await call<ApiIncident>('GET', `/${enc(id)}`);
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
};
