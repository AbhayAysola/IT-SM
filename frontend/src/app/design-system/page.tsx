import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { DesignSystemPreview } from './preview';

export const metadata: Metadata = {
  title: 'Design system | IT-SM',
  robots: { index: false, follow: false },
};

// Evaluated on every request (not at build time) so staging can opt in with a runtime env var.
export const dynamic = 'force-dynamic';

/**
 * Development-only. Reachable at /design-system when NODE_ENV !== 'production', or in a production
 * build only if the server is started with ENABLE_DESIGN_SYSTEM=true. Otherwise it is a 404.
 */
export default function DesignSystemPage() {
  const enabled = process.env.NODE_ENV !== 'production' || process.env.ENABLE_DESIGN_SYSTEM === 'true';
  if (!enabled) notFound();
  return <DesignSystemPreview />;
}
