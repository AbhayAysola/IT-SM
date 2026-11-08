import type { Metadata } from 'next';
import { AdminShell } from '@/features/admin/components/admin-shell';

export const metadata: Metadata = { title: 'Administration | IT-SM' };

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return <AdminShell>{children}</AdminShell>;
}
