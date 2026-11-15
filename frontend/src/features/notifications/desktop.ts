/**
 * Desktop notifications (opt-in): shown for new notifications that arrive in real time while IT-SM is open in a tab or
 * as the installed app, but not looked at. Through the service worker when there is one (needed on Android and in the
 * installed app), else the page's Notification API. Clicking one opens its incident.
 */

export type DesktopPermission = 'unsupported' | 'default' | 'granted' | 'denied';

export function desktopPermission(): DesktopPermission {
  if (typeof window === 'undefined' || !('Notification' in window)) return 'unsupported';
  return Notification.permission as DesktopPermission;
}

export async function requestDesktopPermission(): Promise<DesktopPermission> {
  if (desktopPermission() === 'unsupported') return 'unsupported';
  return (await Notification.requestPermission()) as DesktopPermission;
}

/** Only when allowed, and only when the person is not already looking at the app (the bell shows it then). */
export function shouldShowDesktop(permission: DesktopPermission, visibility: DocumentVisibilityState): boolean {
  return permission === 'granted' && visibility === 'hidden';
}

export async function showDesktop(title: string, body: string, url: string | null, tag: string): Promise<void> {
  const options: NotificationOptions = { body, tag, icon: '/icons/icon-192.png', badge: '/icons/icon-192.png', data: { url: url ?? '/notifications' } };
  try {
    const reg = 'serviceWorker' in navigator ? await navigator.serviceWorker.getRegistration() : undefined;
    if (reg) { await reg.showNotification(title, options); return; }
  } catch { /* fall through to the page API */ }
  const n = new Notification(title, options);
  n.onclick = () => { window.focus(); window.location.assign(url ?? '/notifications'); n.close(); };
}
