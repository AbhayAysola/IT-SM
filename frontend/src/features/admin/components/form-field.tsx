import React from 'react';
import { Label } from '@/components/ui/label';

/** Label + control + inline error, wired with aria-describedby. Pass the same `id` to the control. */
export function FormField({
  id, label, error, hint, children,
}: { id: string; label: string; error?: string; hint?: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      {children}
      {hint && !error && <p id={`${id}-hint`} className="text-xs text-muted-foreground">{hint}</p>}
      {error && <p id={`${id}-error`} role="alert" className="text-sm font-medium text-destructive">{error}</p>}
    </div>
  );
}
