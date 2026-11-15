import type { MetadataRoute } from 'next';

/**
 * The web app manifest (served at /manifest.webmanifest): lets IT-SM be installed as an app (PWA, P4). It opens on the
 * dashboard in its own window; the top bar colour is the theme colour, so the installed window matches the app.
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: 'IT-SM | IT Service Management',
    short_name: 'IT-SM',
    description: 'Report IT problems, follow their progress, and resolve incidents within their service levels.',
    id: '/',
    start_url: '/dashboard',
    scope: '/',
    display: 'standalone',
    background_color: '#ffffff',
    theme_color: '#0f172a',
    categories: ['business', 'productivity'],
    icons: [
      { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
      { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: '/icons/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
  };
}
