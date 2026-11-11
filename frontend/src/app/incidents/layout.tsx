import type { Metadata } from 'next';
import { WorkspaceShell } from '@/features/incidents/components/workspace-shell';

export const metadata: Metadata = { title: 'Incidents | IT-SM' };

export default function IncidentsLayout({ children }: { children: React.ReactNode }) {
  return <WorkspaceShell>{children}</WorkspaceShell>;
}
