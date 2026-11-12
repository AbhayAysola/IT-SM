'use client';

import React from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ServiceError } from '../types';

/** The error a form shows after a failed save: the safe message, plus the two explicit ways out of a stale version. */
export function FormError({
  error, onKeepMine, onLoadLatest, busy,
}: {
  error: ServiceError | null;
  /** Re-fetch the record and keep what the user typed, on top of the latest version number. */
  onKeepMine?: () => void;
  /** Re-fetch the record and replace the form with the latest values. */
  onLoadLatest?: () => void;
  busy?: boolean;
}) {
  if (!error) return null;
  const stale = error.code === 'STALE';
  return (
    <div role="alert" data-testid="form-error" className="space-y-2 rounded-md border border-destructive/30 bg-destructive/10 p-3 text-sm text-destructive">
      <p className="font-medium">{error.message}</p>
      {stale && (onKeepMine || onLoadLatest) && (
        <div className="flex flex-wrap gap-2">
          {onKeepMine && (
            <Button type="button" size="sm" variant="outline" onClick={onKeepMine} disabled={busy}>
              {busy && <Loader2 className="animate-spin" aria-hidden="true" />} Keep my edits on the latest version
            </Button>
          )}
          {onLoadLatest && (
            <Button type="button" size="sm" variant="outline" onClick={onLoadLatest} disabled={busy}>Discard mine and load latest</Button>
          )}
        </div>
      )}
    </div>
  );
}

/** Merges server field errors (their names are the request's) into the form's field map without clobbering local ones. */
export const withServerFields = (local: Record<string, string>, error: ServiceError): Record<string, string> => ({ ...error.fieldErrors, ...local });
