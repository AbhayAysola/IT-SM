import { User } from '@/types';

/**
 * Session state on the client.
 *
 * The session itself is an HttpOnly cookie that the browser holds and attaches to API calls; JavaScript can neither read nor write it,
 * so there is NO token in this module, in localStorage, in sessionStorage or in `document.cookie`. What the client can know is only what
 * `GET /api/users/me` last said, kept in memory for display. That cached user NEVER establishes authentication: every protected screen
 * asks `/api/users/me` (see AuthGuard) before it renders, and the backend is the only authority.
 */
let cachedUser: User | null = null;

export function getCachedUser(): User | null {
  return cachedUser;
}

export function setCachedUser(user: User): void {
  cachedUser = user;
}

/** Keys that earlier versions of the app used to keep the JWT (and the user) where scripts can read them. */
const LEGACY_STORAGE_KEYS = ['it_sm_token', 'it_sm_user'];
const LEGACY_COOKIE = 'it_sm_token';

/** Removes any credential an older version left in JS-readable storage, so an upgrade does not keep a token lying around. */
export function purgeLegacyCredentials(): void {
  if (typeof window === 'undefined') return;
  try {
    for (const key of LEGACY_STORAGE_KEYS) localStorage.removeItem(key);
  } catch {
    // storage may be unavailable (private mode); nothing to purge then
  }
  try {
    document.cookie = `${LEGACY_COOKIE}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; SameSite=Lax`;
  } catch {
    // ignore
  }
}

/** Forgets everything the client knows about the signed-in user (logout, and any 401). The server cookie is ended by `endSession`. */
export function clearSession(): void {
  cachedUser = null;
  purgeLegacyCredentials();
}
