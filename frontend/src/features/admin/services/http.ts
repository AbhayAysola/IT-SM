import { ApiErrorResponse, request } from '@/lib/api';
import { clearSession } from '@/lib/auth';
import { ServiceError } from '../types';

export type QueryValue = string | number | boolean | null | undefined;

/** `?a=1&b=x%20y`, skipping null/undefined/blank values; keys and values are percent-encoded. Empty -> ''. */
export function toQuery(params: Record<string, QueryValue>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === null || v === undefined) continue;
    const s = String(v).trim();
    if (s === '') continue;
    q.set(k, s);
  }
  const text = q.toString();
  return text ? `?${text}` : '';
}

/** One failed backend call -> one `ServiceError` with a message that is safe to put on screen. */
export function toServiceError(e: unknown): ServiceError {
  if (e instanceof ServiceError) return e;
  if (!(e instanceof ApiErrorResponse)) {
    return new ServiceError('UNAVAILABLE', 'We couldn’t reach the server. Check your connection and try again.');
  }
  const fieldErrors: Record<string, string> = {};
  for (const f of e.fieldErrors) if (!(f.field in fieldErrors)) fieldErrors[f.field] = f.message;
  const opts = { status: e.status, fieldErrors };
  switch (e.status) {
    case 401:
      // Same handling as the route guard: forget the dead session, or sign-in would bounce straight back.
      clearSession();
      if (typeof window !== 'undefined') {
        window.location.assign(`/?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`);
      }
      return new ServiceError('UNAUTHENTICATED', 'Your session has expired. Please sign in again.', opts);
    case 403:
      return new ServiceError('FORBIDDEN', 'You don’t have permission to do this.', opts);
    case 404:
      return new ServiceError('NOT_FOUND', e.message || 'That record no longer exists.', opts);
    case 400:
      return new ServiceError('VALIDATION', e.message, opts);
    case 409:
      if (e.code === 'CONCURRENT_MODIFICATION') return new ServiceError('STALE', e.message, opts);
      if (e.code === 'DUPLICATE_RESOURCE') return new ServiceError('DUPLICATE', e.message, opts);
      return new ServiceError('CONFLICT', e.message, opts);
    case 422:
      return new ServiceError('UNPROCESSABLE', e.message, opts);
    case 503:
      return new ServiceError('DEPENDENCY_UNAVAILABLE', e.message || 'A service this depends on is not available yet.', opts);
    default:
      return new ServiceError('UNAVAILABLE', 'Something went wrong on our side. Please try again.', opts);
  }
}

export interface CallOptions {
  method?: 'GET' | 'POST' | 'PUT';
  query?: Record<string, QueryValue>;
  body?: unknown;
}

/**
 * The only way admin code talks to the backend: the app's single `request` client plus query encoding, JSON bodies
 * and error mapping. No tokens or bodies are ever logged.
 */
export async function call<T>(path: string, { method = 'GET', query, body }: CallOptions = {}): Promise<T> {
  try {
    return await request<T>(`${path}${query ? toQuery(query) : ''}`, {
      method,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
  } catch (e) {
    throw toServiceError(e);
  }
}

/** Structural check for list endpoints: refuses a body that is not the page envelope instead of rendering garbage. */
export function assertPage<T>(body: unknown, isItem: (x: unknown) => x is T): asserts body is { items: T[]; page: number; size: number; totalItems: number; totalPages: number } {
  const b = body as { items?: unknown; totalItems?: unknown; page?: unknown };
  if (!b || !Array.isArray(b.items) || typeof b.totalItems !== 'number' || typeof b.page !== 'number' || !b.items.every(isItem)) {
    throw new ServiceError('UNAVAILABLE', 'Something went wrong on our side. Please try again.');
  }
}

export const isObj = (x: unknown): x is Record<string, unknown> => !!x && typeof x === 'object';
export const hasNum = (x: unknown, k: string) => isObj(x) && typeof x[k] === 'number';
export const hasStr = (x: unknown, k: string) => isObj(x) && typeof x[k] === 'string';
