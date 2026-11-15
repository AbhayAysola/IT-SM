/*
 * IT-SM service worker (P4, PWA). Deliberately small:
 *  - page loads go to the network as usual; only when that fails (offline) is the offline page shown;
 *  - API calls and assets are never intercepted or cached, so nobody ever sees stale incident data;
 *  - clicking a desktop notification focuses an open IT-SM window on its incident, or opens one.
 */
const CACHE = 'itsm-shell-v1';
const OFFLINE = '/offline.html';

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll([OFFLINE, '/icons/icon-192.png'])));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  if (event.request.mode !== 'navigate') return;
  event.respondWith(fetch(event.request).catch(() => caches.match(OFFLINE)));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const url = (event.notification.data && event.notification.data.url) || '/notifications';
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      for (const w of windows) {
        if ('focus' in w) return w.navigate(url).then((c) => (c || w).focus());
      }
      return self.clients.openWindow(url);
    })
  );
});
