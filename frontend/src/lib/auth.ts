import { User } from '@/types';

export const TOKEN_KEY = 'it_sm_token';
export const USER_KEY = 'it_sm_user';

/**
 * Retrieve the authentication token.
 * Checks localStorage first, then falls back to cookies for SSR/browser compatibility.
 */
export function getToken(): string | null {
  if (typeof window === 'undefined') {
    return null;
  }

  // 1. Try localStorage
  try {
    const local = localStorage.getItem(TOKEN_KEY);
    if (local) return local;
  } catch {
    // LocalStorage might be disabled or restricted in private browsing
  }

  // 2. Try cookie
  try {
    const cookies = document.cookie ? document.cookie.split('; ') : [];
    for (const cookie of cookies) {
      const [name, ...rest] = cookie.split('=');
      if (name === TOKEN_KEY) {
        return decodeURIComponent(rest.join('='));
      }
    }
  } catch {
    // Ignore cookie read failure
  }

  return null;
}

/**
 * Persist authentication token in both localStorage and browser cookies.
 */
export function setToken(token: string): void {
  if (typeof window === 'undefined') return;

  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch (e) {
    console.error('Failed to save token to localStorage:', e);
  }

  try {
    const maxAge = 7 * 24 * 60 * 60; // 7 days
    document.cookie = `${TOKEN_KEY}=${encodeURIComponent(token)}; path=/; max-age=${maxAge}; SameSite=Lax`;
  } catch (e) {
    console.error('Failed to save token to cookie:', e);
  }
}

/**
 * Clear authentication token from both localStorage and cookies.
 */
export function clearToken(): void {
  if (typeof window === 'undefined') return;

  try {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  } catch (e) {
    console.error('Failed to clear localStorage auth:', e);
  }

  try {
    document.cookie = `${TOKEN_KEY}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; SameSite=Lax`;
  } catch (e) {
    console.error('Failed to clear cookie:', e);
  }
}

/**
 * Quick check if user has active token
 */
export function isAuthenticated(): boolean {
  return !!getToken();
}

/**
 * Optional cached user helpers for rapid UI rendering
 */
export function getCachedUser(): User | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function setCachedUser(user: User): void {
  if (typeof window === 'undefined') return;
  try {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  } catch {
    // Ignore storage errors
  }
}
