import { Suspense } from 'react';
import TechniciansPage from '@/features/admin/technicians/technicians-page';

export const metadata = { title: 'Technicians | IT-SM Administration' };

export default function Page() {
  return (
    <Suspense fallback={null}>
      <TechniciansPage />
    </Suspense>
  );
}
