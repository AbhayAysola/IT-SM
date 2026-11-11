import { IncidentError, type Feedback, type Incident, type IncidentErrorCode, type IncidentState, type Priority, type Recommendation, type SimilarResolution, type TimelineEntry } from '../model/types';

/**
 * Pure translation between the backend's JSON (`IncidentController` DTOs) and the UI model. No network, so it is
 * unit-tested without a backend. The backend identifies people by id and returns no names: until it does, the UI
 * model's email/name fields are filled by `who()` (see the "bridge" note in docs/architecture/p3.md).
 */

export interface ApiIncident {
  id: string; title: string; description: string; createdAt: string; updatedAt: string; state: IncidentState;
  reporterId: number; assigneeId: number | null; categoryId: number | null; serviceId: number | null; priority: Priority | null;
  assignedAt: string | null; firstResponseAt: string | null; resolvedAt: string | null; closedAt: string | null;
  availableTransitions: string[];
}
export interface ApiTimelineEntry { type: TimelineEntry['type']; authorId: number; visibility: TimelineEntry['visibility']; body: string; incidentState: IncidentState; createdAt: string }
export interface ApiFeedback { rating: number; comment: string | null; submittedAt: string }
export interface ApiRecommendation { technicianId: number; name: string; score: number; reasons: string[] }
export interface ApiResolved { incidentId: string; title: string; resolution: string; resolvedAt: string; sameService: boolean }

/** The signed-in user, from GET /api/users/me. */
export interface Me { id: number; email: string; name: string }

const LIVE_DOMAIN = '@live.invalid';
/** A stand-in address for a user we only know by id. `.invalid` can never be a real mailbox. */
export const liveEmail = (id: number) => `user-${id}${LIVE_DOMAIN}`;
export const liveId = (email: string): number | null => {
  const m = /^user-(\d+)@live\.invalid$/.exec(email);
  return m ? Number(m[1]) : null;
};

/** Who a user id is, as far as we know. The signed-in user is the only one with a real name and email. */
export const who = (me: Me, id: number | null): { email: string; name: string } | null =>
  id === null ? null : id === me.id ? { email: me.email, name: me.name } : { email: liveEmail(id), name: `User #${id}` };

export function toIncident(d: ApiIncident, me: Me): Incident {
  const reporter = who(me, d.reporterId)!;
  const assignee = who(me, d.assigneeId);
  return {
    id: d.id, title: d.title, description: d.description,
    // Names are not returned yet (the API has ids only), so the id stands in. See the TODO list.
    category: d.categoryId === null ? 'Not categorised' : `Category #${d.categoryId}`,
    service: d.serviceId === null ? 'Not set' : `Service #${d.serviceId}`,
    priority: d.priority ?? 'P3', state: d.state,
    reporterEmail: reporter.email, reporterName: reporter.name,
    assigneeEmail: assignee?.email ?? null, assigneeName: assignee?.name ?? null,
    createdAt: d.createdAt, updatedAt: d.updatedAt, resolvedAt: d.resolvedAt,
  };
}

export function toTimelineEntry(d: ApiTimelineEntry, me: Me, incidentId: string, index: number): TimelineEntry {
  const author = who(me, d.authorId)!;
  return {
    id: `${incidentId}-${index}`, incidentId, authorEmail: author.email, authorName: author.name, type: d.type,
    visibility: d.visibility, body: d.body, incidentState: d.incidentState, createdAt: d.createdAt,
  };
}

export const toFeedback = (d: ApiFeedback, me: Me, incidentId: string): Feedback =>
  ({ incidentId, reporterEmail: me.email, rating: d.rating, comment: d.comment, submittedAt: d.submittedAt });

export const toRecommendation = (d: ApiRecommendation): Recommendation =>
  ({ technicianEmail: liveEmail(d.technicianId), name: d.name, team: '', score: d.score, reasons: d.reasons });

/** The API does not say who fixed it, its priority or how long it took (yet), so the UI shows only what it knows. */
export const toSimilar = (d: ApiResolved): SimilarResolution =>
  ({ incidentId: d.incidentId, title: d.title, priority: null, sameService: d.sameService, resolution: d.resolution, resolvedBy: null, daysToResolve: null });

// ---- errors -------------------------------------------------------------------------------------------------------

const CODES: Record<string, IncidentErrorCode> = {
  RESOURCE_NOT_FOUND: 'NOT_FOUND', SERVICE_NOT_FOUND: 'NOT_FOUND',
  FORBIDDEN: 'FORBIDDEN', UNAUTHENTICATED: 'FORBIDDEN', INVALID_CREDENTIALS: 'FORBIDDEN',
  VALIDATION_FAILED: 'VALIDATION', INVALID_INPUT: 'VALIDATION', MALFORMED_REQUEST: 'VALIDATION',
  CONFLICT: 'CONFLICT', DUPLICATE_RESOURCE: 'CONFLICT', CONCURRENT_MODIFICATION: 'CONFLICT', REFERENCE_CONSTRAINT: 'CONFLICT',
  INVALID_STATE_TRANSITION: 'CONFLICT', TECHNICIAN_UNAVAILABLE: 'CONFLICT', WORKLOAD_EXCEEDED: 'CONFLICT',
  SERVICE_UNAVAILABLE: 'UNAVAILABLE', EXTERNAL_SERVICE_FAILURE: 'UNAVAILABLE', INTERNAL_ERROR: 'UNAVAILABLE',
};

/** Turns an RFC 7807 body (`code`, `detail`, `errors[{field,message}]`) into the error the pages already handle. */
export function toIncidentError(status: number, body: unknown): IncidentError {
  const b = (body && typeof body === 'object' ? body : {}) as { code?: unknown; detail?: unknown; errors?: unknown };
  const code = (typeof b.code === 'string' && CODES[b.code]) || (status === 404 ? 'NOT_FOUND' : status === 401 || status === 403 ? 'FORBIDDEN' : status === 409 ? 'CONFLICT' : status >= 500 ? 'UNAVAILABLE' : 'VALIDATION');
  const message = typeof b.detail === 'string' && b.detail ? b.detail : 'Something went wrong. Please try again.';
  const fields: Record<string, string> = {};
  if (Array.isArray(b.errors)) for (const e of b.errors) if (e && typeof e.field === 'string' && typeof e.message === 'string') fields[e.field] = e.message;
  return new IncidentError(code, message, Object.keys(fields).length ? fields : undefined);
}
