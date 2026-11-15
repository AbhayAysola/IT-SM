'use client';

import React, { useEffect, useState } from 'react';
import { BellRing } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { desktopPermission, requestDesktopPermission, type DesktopPermission } from './desktop';

const STATUS: Record<DesktopPermission, string> = {
  unsupported: 'This browser cannot show desktop notifications.',
  default: 'Get a desktop notification when something new arrives while IT-SM is open in the background.',
  granted: 'Desktop notifications are on for new notifications while IT-SM is open in the background.',
  denied: 'Desktop notifications are blocked. Allow them for this site in your browser settings to turn them on.',
};

/** Opt-in for desktop notifications. The browser asks the person; nothing is ever shown without their permission. */
export function DesktopNotificationsToggle() {
  // Read after mount: the Notification API only exists in the browser.
  const [permission, setPermission] = useState<DesktopPermission | null>(null);
  useEffect(() => setPermission(desktopPermission()), []);
  if (!permission) return null;
  return (
    <div className="mb-4 flex flex-wrap items-center gap-3 rounded-lg border bg-card p-3 text-sm" data-testid="desktop-notifications">
      <BellRing className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      <p className="min-w-0 flex-1" role="status">{STATUS[permission]}</p>
      {permission === 'default' && (
        <Button size="sm" variant="outline" onClick={async () => setPermission(await requestDesktopPermission())}>Turn on desktop notifications</Button>
      )}
    </div>
  );
}
