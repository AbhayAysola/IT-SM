import { WorkspaceShell } from '@/features/incidents/components/workspace-shell';
import DashboardPage from '@/features/dashboard/dashboard-page';

export const metadata = { title: 'Dashboard | IT-SM' };

export default function Page() {
  return (
    <WorkspaceShell>
      <DashboardPage />
    </WorkspaceShell>
  );
}
