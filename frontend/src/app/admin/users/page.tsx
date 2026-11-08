import { Suspense } from 'react';
import UsersPage from '@/features/admin/users/users-page';

export const metadata = { title: 'Users | IT-SM Administration' };

export default function Page() {
  return (
    <Suspense fallback={null}>
      <UsersPage />
    </Suspense>
  );
}
