import { LoginRequest, RegisterRequest, RegistrationResponse, User, WebLoginResponse } from '@/types';
import { clearSession } from './auth';

export const API_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';

export interface ApiFieldError {
  field: string;
  message: string;
}

export class ApiErrorResponse extends Error {
  status: number;
  /** Stable machine code of the backend's problem+json body (e.g. CONCURRENT_MODIFICATION), when there is one. */
  code?: string;
  /** Per-field validation messages of the problem body, when there are any. */
  fieldErrors: ApiFieldError[];
  constructor(message: string, status: number, code?: string, fieldErrors: ApiFieldError[] = []) {
    super(message);
    this.name = 'ApiErrorResponse';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

/**
 * The one HTTP client. Authentication is the HttpOnly session cookie: `credentials: 'include'` makes the browser send it (also to the
 * API origin in development), and `X-ITSM-Client: web` is sent on every call: on login it selects cookie mode, and on every unsafe request
 * it is the CSRF header the backend requires together with an allowed `Origin` (which the browser adds itself). There is no token here.
 */
export async function request<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const url = `${API_URL}${endpoint}`;

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    'X-ITSM-Client': 'web',
    ...(options.headers as Record<string, string>),
  };

  const response = await fetch(url, { ...options, headers, credentials: 'include' });

  if (!response.ok) {
    // A 401 from anything but the login form means the session is gone: forget the cached user so it can never pose as one.
    if (response.status === 401 && !endpoint.startsWith('/api/auth/login')) clearSession();
    let errorMessage = `HTTP ${response.status}: ${response.statusText}`;
    let code: string | undefined;
    let fieldErrors: ApiFieldError[] = [];
    try {
      const errorData = await response.json();
      errorMessage = errorData.message || errorData.error || JSON.stringify(errorData);
      if (typeof errorData.code === 'string') code = errorData.code;
      if (Array.isArray(errorData.errors)) {
        fieldErrors = errorData.errors.filter(
          (e: unknown): e is ApiFieldError =>
            !!e && typeof (e as ApiFieldError).field === 'string' && typeof (e as ApiFieldError).message === 'string'
        );
      }
    } catch {
      // use statusText fallback
    }
    throw new ApiErrorResponse(errorMessage, response.status, code, fieldErrors);
  }

  if (response.status === 204 || response.headers.get('content-length') === '0') return {} as T;
  return response.json();
}

/**
 * POST /api/auth/register. 202 {status:'ACCEPTED'} whether or not the address was already registered (no account-existence
 * signal, no token): the caller must sign in afterwards. 404 when the deployment has self-registration switched off.
 */
export async function register(data: RegisterRequest): Promise<RegistrationResponse> {
  return request<RegistrationResponse>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/** POST /api/auth/login in browser mode: the session arrives as an HttpOnly cookie, the body is only `{user}` (never a token). */
export async function login(data: LoginRequest): Promise<WebLoginResponse> {
  return request<WebLoginResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/** GET /api/users/me */
export async function getMe(): Promise<User> {
  return request<User>('/api/users/me');
}

/** POST /api/auth/logout: revokes the user's sessions and clears the cookie. Prefer `endSession()` from `lib/session`. */
export async function logout(): Promise<{ status: string }> {
  return request<{ status: string }>('/api/auth/logout', { method: 'POST' });
}

export const api = { register, login, getMe, logout };
export default api;
