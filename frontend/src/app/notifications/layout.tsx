import type { Metadata } from 'next';
import { WorkspaceShell } from '@/features/incidents/components/workspace-shell';

export const metadata: Metadata = { title: 'Notifications | IT-SM' };

export default function NotificationsLayout({ children }: { children: React.ReactNode }) {
  return <WorkspaceShell>{children}</WorkspaceShell>;
}
