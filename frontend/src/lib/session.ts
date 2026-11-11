import { api } from './api';
import { clearSession } from './auth';

/**
 * Signs out for real: asks the backend to revoke the session (it bumps the user's token version, which ends EVERY session and token
 * of that user, not just this browser's) and to clear the HttpOnly cookie, then forgets the cached user. If the server cannot be
 * reached the client still forgets what it knows, but the cookie then stays valid until it expires or a later logout succeeds.
 */
export async function endSession(): Promise<void> {
  try {
    await api.logout();
  } catch {
    // offline or already signed out (401): the local state is cleared below either way
  } finally {
    clearSession();
  }
}
