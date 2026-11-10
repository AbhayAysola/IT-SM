import { ApiErrorResponse, request } from '@/lib/api';
import { clearToken } from '@/lib/auth';
import { ServiceError } from '../types';
import { toSearchParams, type AuditEvent, type AuditPage, type AuditQuery } from '../audit/audit-types';

/**
 * Read-only client for the backend audit API. There is deliberately no method that writes: the endpoints are GET only
 * and the table is append-only in the database.
 *
 * Errors become `ServiceError`s with user-safe messages. Raw server text is only passed through for 400s (validation
 * messages written for clients); 5xx and network failures never leak internals.
 */
const BASE = '/api/admin/audit-events';

function toServiceError(e: unknown): ServiceError {
  if (e instanceof ServiceError) return e;
  if (e instanceof ApiErrorResponse) {
    if (e.status === 401) {
      // Same handling as the route guard: drop the dead token, or sign-in would bounce straight back.
      clearToken();
      if (typeof window !== 'undefined') window.location.assign(`/?redirect=${encodeURIComponent('/admin/audit-logs')}`);
      return new ServiceError('UNAUTHENTICATED', 'Your session has expired. Please sign in again.');
    }
    if (e.status === 403) return new ServiceError('FORBIDDEN', 'You don’t have permission to view audit logs.');
    if (e.status === 404) return new ServiceError('NOT_FOUND', 'That audit entry no longer exists.');
    if (e.status === 400) return new ServiceError('VALIDATION', e.message);
  }
  return new ServiceError('UNAVAILABLE', 'The audit log is unavailable right now. Please try again.');
}

const isEvent = (x: unknown): x is AuditEvent =>
  !!x && typeof x === 'object' && typeof (x as AuditEvent).id === 'string' && typeof (x as AuditEvent).occurredAt === 'string'
  && !!(x as AuditEvent).actor && typeof (x as AuditEvent).action === 'string';

export const auditApi = {
  async list(query: AuditQuery): Promise<AuditPage> {
    try {
      const body = await request<AuditPage>(`${BASE}?${toSearchParams(query)}`);
      if (!body || !Array.isArray(body.items) || !body.items.every(isEvent) || typeof body.totalItems !== 'number') {
        throw new ServiceError('UNAVAILABLE', 'unexpected response');
      }
      return body;
    } catch (e) {
      throw toServiceError(e);
    }
  },

  async get(id: string): Promise<AuditEvent> {
    try {
      const body = await request<AuditEvent>(`${BASE}/${encodeURIComponent(id)}`);
      if (!isEvent(body)) throw new ServiceError('UNAVAILABLE', 'unexpected response');
      return body;
    } catch (e) {
      throw toServiceError(e);
    }
  },
};
