'use client';

import { useEffect } from 'react';

/**
 * Registers IT-SM's service worker (public/sw.js) in production builds: it makes the app installable with an offline
 * page and lets desktop notifications open their incident. Development keeps no worker, so hot reload is never cached.
 */
export function ServiceWorkerRegistrar() {
  useEffect(() => {
    if (process.env.NODE_ENV !== 'production' || !('serviceWorker' in navigator)) return;
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).catch(() => undefined);
  }, []);
  return null;
}
