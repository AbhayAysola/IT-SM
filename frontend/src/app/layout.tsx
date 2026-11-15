import type { Metadata, Viewport } from 'next';
import { Inter } from 'next/font/google';
import './globals.css';
import { ServiceWorkerRegistrar } from '@/components/pwa/service-worker-registrar';

const inter = Inter({
  subsets: ['latin'],
  variable: '--font-inter',
  display: 'swap',
});

export const metadata: Metadata = {
  title: 'IT-SM | IT Service Management',
  description: 'Intelligent IT Service Management & Incident Resolution System',
  // Installable app (PWA): the manifest is app/manifest.ts and the iOS icon app/apple-icon.png; iOS reads these tags.
  appleWebApp: { capable: true, title: 'IT-SM', statusBarStyle: 'black-translucent' },
};

export const viewport: Viewport = { themeColor: '#0f172a' };

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className={inter.variable}>
      <body className="min-h-screen">
        {children}
        <ServiceWorkerRegistrar />
      </body>
    </html>
  );
}
